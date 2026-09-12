package io.dilfi5h.agentping.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationCompat
import io.dilfi5h.agentping.MainActivity
import io.dilfi5h.agentping.R
import io.dilfi5h.agentping.data.AppDatabase
import io.dilfi5h.agentping.data.NtfyFrame
import io.dilfi5h.agentping.data.SettingsStore
import io.dilfi5h.agentping.data.parsePayload
import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.net.NtfyStream
import io.dilfi5h.agentping.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsStore
    private lateinit var db: AppDatabase
    private lateinit var stream: NtfyStream

    private val kick = MutableStateFlow(0)
    private var loopJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.log("SVC", "onCreate")
        settings = SettingsStore(this)
        db = AppDatabase.get(this)
        stream = NtfyStream(scope, ::onFrame, ::onConnState)
        createChannels()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.log("SVC", "onStartCommand action=${intent?.action ?: "null"} startId=$startId")
        startAsForeground()
        // 幂等：重复 start 不叠加循环；reload/refresh（配置变更或下拉刷新）取消旧循环重开
        if (intent?.action == ACTION_REFRESH) sinceOverride.value = SINCE_BACKFILL
        val reload = intent?.action == ACTION_RELOAD || intent?.action == ACTION_REFRESH
        if (reload) loopJob?.cancel()
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        AppLog.log("SVC", "onDestroy")
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val n = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("AgentPing 运行中")
            .setContentText(connectionState.value)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(mainIntent())
            .build()
        startForeground(NOTIF_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** 订阅主循环：指数退避重连；open 成功后重置；网络恢复 kick 立即重试。 */
    private suspend fun runLoop() {
        var backoff = 1000L
        var seenKick = kick.value
        while (scope.isActive) {
            val cfg = settings.flow.first { it.configured }
            // 优先级：下拉刷新强制回放 > 本地最后 id（断线续传） > 首次连接回放 12h 缓存
            val forced = sinceOverride.value
            if (forced != null) sinceOverride.value = null
            val since = forced ?: db.dao().lastId() ?: SINCE_BACKFILL
            AppLog.log("LOOP", "connecting since=$since backoff=${backoff}ms")
            val opened = stream.connectOnce(cfg, since).let {
                it is io.dilfi5h.agentping.net.StreamEnd.Closed && it.opened
            }
            AppLog.log("LOOP", "ended opened=$opened")
            if (opened) { backoff = 1000L; continue }
            updateServiceNotification()
            // 退避等待；期间网络恢复（kick 计数变化）则立即重试且不累加退避
            val kicked = withTimeoutOrNull(backoff) { kick.first { it != seenKick } } != null
            if (kicked) { AppLog.log("LOOP", "kick: retry now"); seenKick = kick.value }
            else { AppLog.log("LOOP", "backoff -> ${backoff * 2}ms"); backoff = (backoff * 2).coerceAtMost(60_000L) }
        }
    }

    private suspend fun onFrame(frame: NtfyFrame) {
        val payload = parsePayload(frame.message)
        val entity = MessageEntity(
            id = frame.id,
            time = frame.time * 1000,
            topic = frame.topic,
            title = frame.title,
            raw = frame.message,
            agent = payload?.agent,
            host = payload?.host,
            state = payload?.state,
            task = payload?.task,
            detail = payload?.detail,
            session = payload?.session,
            ts = payload?.ts,
            dur = payload?.dur,
        )
        if (entity.id.isBlank()) { AppLog.log("DB", "skip blank id"); return }
        if (db.deletedDao().exists(entity.id)) {
            AppLog.log("DB", "tombstoned id=${entity.id}, skip (user deleted)")
            return
        }
        val rowId = db.dao().insert(entity) // ntfy id 幂等，重复消息 IGNORE 返回 -1
        if (rowId == -1L) {
            AppLog.log("DB", "dup id=${entity.id}")
            return
        }
        AppLog.log("DB", "new id=${entity.id} state=${entity.state} host=${entity.host}")
        notifyMessage(entity)
        pruneOld()
    }

    private suspend fun pruneOld() {
        // cache-duration 12h，本地历史略长：留 3 天
        db.dao().prune(System.currentTimeMillis() - 3 * 24 * 3600_000L)
        db.deletedDao().prune(System.currentTimeMillis() - 2 * 24 * 3600_000L)
    }

    // ---- 通知 ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "任务状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "失败与等待", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "服务运行", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun notifyMessage(m: MessageEntity) {
        val alert = m.stateKind == io.dilfi5h.agentping.data.StateKind.WAITING ||
            m.stateKind == io.dilfi5h.agentping.data.StateKind.FAILED
        val title = m.title ?: "[${m.host ?: "?"}] ${m.agent ?: "shell"} ${m.stateKind.label}"
        val text = listOfNotNull(m.task, m.detail).joinToString("\n")
            .ifBlank { m.raw ?: "" }
        val n = NotificationCompat.Builder(this, if (alert) CH_ALERT else CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(mainIntent())
            .setCategory(
                if (alert) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_STATUS
            )
            .build()
        try {
            AppLog.log("NOTIF", "post ${if (alert) "alert" else "status"}: $title")
            getSystemService(NotificationManager::class.java).notify(m.id.hashCode(), n)
        } catch (e: SecurityException) {
            AppLog.log("NOTIF", "no permission: ${e.message}")
            // POST_NOTIFICATIONS 未授予：消息仍在时间线里
        }
    }

    private fun updateServiceNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_SERVICE, buildServiceNotification())
    }

    private fun buildServiceNotification(): Notification =
        NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle("AgentPing 运行中")
            .setContentText(connectionState.value)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(mainIntent())
            .build()

    // ---- 连接状态（UI 与前台通知共用）----

    private fun onConnState(s: String) {
        AppLog.log("STATE", s)
        connectionState.value = s
        scope.launch { updateServiceNotification() }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    kick.value += 1 // 网络恢复，立即尝试重连
                }
            }
        )
    }

    private fun mainIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALERT = "alert"
        const val CH_SERVICE = "service"
        const val NOTIF_SERVICE = 1

        /** UI 直读的连接状态文本。 */
        val connectionState = MutableStateFlow("未连接")

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PingService::class.java))
        }

        /** 配置变更后调用：取消旧连接循环，立即按新配置重连。 */
        fun reload(ctx: Context) {
            val i = Intent(ctx, PingService::class.java).setAction(ACTION_RELOAD)
            ctx.startForegroundService(i)
        }

        /** 下拉刷新：重连并回放最近缓存（since=12h），补齐装包前/断线期间的消息。 */
        fun refresh(ctx: Context) {
            val i = Intent(ctx, PingService::class.java).setAction(ACTION_REFRESH)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PingService::class.java))
            connectionState.value = "未连接"
        }

        const val ACTION_RELOAD = "io.dilfi5h.agentping.RELOAD"
        const val ACTION_REFRESH = "io.dilfi5h.agentping.REFRESH"
        const val SINCE_BACKFILL = "12h"

        private val sinceOverride = MutableStateFlow<String?>(null)
    }
}
