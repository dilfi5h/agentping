package io.dilfi5h.agentping

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.dilfi5h.agentping.data.AppDatabase
import io.dilfi5h.agentping.data.SettingsStore
import io.dilfi5h.agentping.notify.CH_ALERT
import io.dilfi5h.agentping.notify.CH_SERVICE
import io.dilfi5h.agentping.notify.CH_STATUS
import io.dilfi5h.agentping.notify.ChannelSnap
import io.dilfi5h.agentping.notify.NotificationHealthSnap
import io.dilfi5h.agentping.notify.diagnoseNotificationHealth
import io.dilfi5h.agentping.svc.PingService
import io.dilfi5h.agentping.ui.AgentPingTheme
import io.dilfi5h.agentping.ui.SettingsScreen
import io.dilfi5h.agentping.ui.TimelineScreen
import io.dilfi5h.agentping.ui.alertChannelSettingsIntent
import io.dilfi5h.agentping.ui.appDetailsIntent
import io.dilfi5h.agentping.ui.appNotificationSettingsIntent
import io.dilfi5h.agentping.ui.ignoreBatteryOptimizationsIntent
import io.dilfi5h.agentping.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private val healthTick = MutableStateFlow(0)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            AppLog.log("UI", "POST_NOTIFICATIONS granted=$it")
            refreshHealth()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore.get(this)
        AppLog.log("UI", "MainActivity onCreate configured=${settings.flow.value.configured}")
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val timeline = AppDatabase.get(this).dao().timeline()
            .stateIn(lifecycleScope, SharingStarted.Lazily, emptyList())

        setContent {
            AgentPingTheme { App(timeline) }
        }

        // 已配置就直接拉起服务；配置保存后由 App 回调重启
        if (settings.flow.value.configured) PingService.start(this)
        refreshHealth()
    }

    override fun onResume() {
        super.onResume()
        refreshHealth()
    }

    @Composable
    private fun App(timeline: kotlinx.coroutines.flow.StateFlow<List<io.dilfi5h.agentping.data.MessageEntity>>) {
        var tab by remember { mutableIntStateOf(0) }
        val settingsState by settings.flow.collectAsState()
        val conn by PingService.connectionState.collectAsState()
        val messages by timeline.collectAsState()
        val tick by healthTick.collectAsState()
        val health = remember(tick) { diagnoseNotificationHealth(readNotificationHealth()) }
        val batteryExempt = remember(tick) {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                        label = { Text("时间线") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.Settings, null) },
                        label = { Text("设置") },
                    )
                }
            },
        ) { pad ->
            if (tab == 0) {
                TimelineScreen(
                    messages = messages,
                    connectionState = conn,
                    onRefresh = { PingService.refresh(this) },
                    onDelete = { id ->
                        lifecycleScope.launch {
                            val db = AppDatabase.get(this@MainActivity)
                            db.deletedDao().insert(
                                io.dilfi5h.agentping.data.DeletedId(id, System.currentTimeMillis())
                            )
                            db.dao().delete(id)
                        }
                    },
                    modifier = Modifier.padding(pad),
                )
            } else {
                SettingsScreen(
                    settings = settingsState,
                    connectionState = conn,
                    health = health,
                    batteryExempt = batteryExempt,
                    logCount = AppLog.size(),
                    onSave = {
                        AppLog.log("UI", "settings saved topic=${it.topic} url=${it.serverUrl}")
                        settings.save(it)
                        PingService.reload(this)
                        tab = 0
                    },
                    onRestartService = {
                        AppLog.log("UI", "manual reconnect")
                        PingService.stop(this)
                        PingService.start(this)
                    },
                    onStopService = { AppLog.log("UI", "manual stop"); PingService.stop(this) },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onPostLocalTest = {
                        AppLog.log("UI", "local test notification")
                        PingService.postLocalTest(this)
                        refreshHealth()
                    },
                    onOpenAppNotificationSettings = {
                        openSettingsOrDetails(appNotificationSettingsIntent(packageName))
                    },
                    onOpenAlertChannelSettings = {
                        openSettingsOrDetails(alertChannelSettingsIntent(packageName))
                    },
                    onRequestIgnoreBatteryOptimizations = {
                        startActivity(ignoreBatteryOptimizationsIntent(packageName))
                    },
                    modifier = Modifier.padding(pad),
                )
            }
        }
    }

    private fun refreshHealth() {
        healthTick.value += 1
    }

    private fun readNotificationHealth(): NotificationHealthSnap {
        val nm = getSystemService(NotificationManager::class.java)
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else null
        AppLog.log(
            "HEALTH",
            "enabled=${nm.areNotificationsEnabled()} paused=${nm.areNotificationsPaused()} " +
                "perm=${granted ?: "n/a"} " +
                "alert=${nm.getNotificationChannel(CH_ALERT)?.importance ?: -1} " +
                "status=${nm.getNotificationChannel(CH_STATUS)?.importance ?: -1} " +
                "service=${nm.getNotificationChannel(CH_SERVICE)?.importance ?: -1}",
        )
        return NotificationHealthSnap(
            sdk = Build.VERSION.SDK_INT,
            postNotificationsGranted = granted,
            appNotificationsEnabled = nm.areNotificationsEnabled(),
            notificationsPaused = nm.areNotificationsPaused(),
            status = channelSnap(nm, CH_STATUS),
            alert = channelSnap(nm, CH_ALERT),
            service = channelSnap(nm, CH_SERVICE),
        )
    }

    private fun channelSnap(nm: NotificationManager, id: String): ChannelSnap {
        val channel = nm.getNotificationChannel(id)
        return ChannelSnap(id = id, exists = channel != null, importance = channel?.importance)
    }

    private fun openSettingsOrDetails(intent: android.content.Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(appDetailsIntent(packageName))
        }
    }
}
