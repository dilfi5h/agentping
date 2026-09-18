package io.dilfi5h.agentping.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.dilfi5h.agentping.data.PingSettings
import io.dilfi5h.agentping.notify.CH_ALERT
import io.dilfi5h.agentping.notify.HealthTone
import io.dilfi5h.agentping.notify.NotificationHealthReport
import io.dilfi5h.agentping.util.AppLog

@Composable
fun SettingsScreen(
    settings: PingSettings,
    connectionState: String,
    health: NotificationHealthReport,
    batteryExempt: Boolean,
    logCount: Int,
    onSave: (PingSettings) -> Unit,
    onRestartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onPostLocalTest: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onOpenAlertChannelSettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var server by remember(settings) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    var topic by remember(settings) { mutableStateOf(settings.topic) }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Server URL") },
            supportingText = { Text("Self-hosted ntfy address, including https://") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Read Token (read-only)") },
            supportingText = { Text("Starts with tk_, read-only. Don't paste the publish token (that's for server hooks; using it here gives a 403)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = { Text("Topic") },
            supportingText = { Text("Defaults to agentping-all (the catch-all topic)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row {
            Button(
                onClick = { onSave(PingSettings(server, token, topic)) },
                modifier = Modifier.weight(1f),
            ) { Text("Save & connect") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onStopService,
                modifier = Modifier.weight(1f),
            ) { Text("Stop") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Connection status", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(connectionState, style = MaterialTheme.typography.bodyLarge)
                if (!settings.configured) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "No token configured yet. After saving, the App keeps one WSS subscription via a foreground service.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedButton(onClick = onRestartService, modifier = Modifier.fillMaxWidth()) {
            Text("Force reconnect")
        }

        NotificationHealthCard(
            health = health,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onPostLocalTest = onPostLocalTest,
            onOpenAppNotificationSettings = onOpenAppNotificationSettings,
            onOpenAlertChannelSettings = onOpenAlertChannelSettings,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Background keep-alive", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (batteryExempt) "✓ Battery optimization: exempt"
                    else "✗ Battery optimization: not exempt (the system will kill background connections)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (batteryExempt) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
                Text(
                    "If background notifications don't arrive, check in order: 1) exempt battery optimization " +
                        "with the button below; 2) allow \"autostart / background running\" in system settings; " +
                        "3) exclude AgentPing from VPN-style apps' split routing " +
                        "(a VPN tunnel reconnect takes this App's connections with it).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!batteryExempt) {
                    Button(onClick = onRequestIgnoreBatteryOptimizations) { Text("Request battery optimization exemption") }
                }
            }
        }

        OutlinedButton(
            onClick = {
                AppLog.log("UI", "log copied (${AppLog.size()} lines)")
                clipboard.setText(AnnotatedString(AppLog.dump()))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy logs ($logCount entries)")
        }

        val version = remember {
            runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        Text(
            "AgentPing v$version · read-only push. Messages whose fields fail to parse are shown as plain text.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun NotificationHealthCard(
    health: NotificationHealthReport,
    onRequestNotificationPermission: () -> Unit,
    onPostLocalTest: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onOpenAlertChannelSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Notification diagnostics", style = MaterialTheme.typography.titleSmall)
            Text(
                health.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = healthToneColor(health.tone),
            )
            health.lines.forEach { line ->
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = healthToneColor(line.tone),
                )
            }
            Text(
                health.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (health.showPermissionRequest) {
                Button(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Request notification permission")
                }
            }
            Button(onClick = onPostLocalTest, modifier = Modifier.fillMaxWidth()) {
                Text("Send test notification")
            }
            OutlinedButton(onClick = onOpenAppNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open app notification settings")
            }
            OutlinedButton(onClick = onOpenAlertChannelSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open the \"Failure & waiting\" channel")
            }
        }
    }
}

@Composable
private fun healthToneColor(tone: HealthTone): Color = when (tone) {
    HealthTone.OK -> MaterialTheme.colorScheme.primary
    HealthTone.WARN -> MaterialTheme.colorScheme.tertiary
    HealthTone.ERROR -> MaterialTheme.colorScheme.error
}

fun appNotificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

fun alertChannelSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, CH_ALERT)

fun appDetailsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

fun ignoreBatteryOptimizationsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
