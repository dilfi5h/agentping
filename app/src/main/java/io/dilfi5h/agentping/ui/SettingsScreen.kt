package io.dilfi5h.agentping.ui

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.dilfi5h.agentping.data.PingSettings
import io.dilfi5h.agentping.util.AppLog

@Composable
fun SettingsScreen(
    settings: PingSettings,
    connectionState: String,
    onSave: (PingSettings) -> Unit,
    onRestartService: () -> Unit,
    onStopService: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var server by remember(settings) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    var topic by remember(settings) { mutableStateOf(settings.topic) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("服务器 URL") },
            supportingText = { Text("自建 ntfy 地址，含 https://") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Read Token（只读）") },
            supportingText = { Text("tk_ 开头，仅读权限；App 无发布能力") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = { Text("Topic") },
            supportingText = { Text("默认 agentping-all（总 topic）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row {
            Button(
                onClick = { onSave(PingSettings(server, token, topic)) },
                modifier = Modifier.weight(1f),
            ) { Text("保存并连接") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onStopService,
                modifier = Modifier.weight(1f),
            ) { Text("停止") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("连接状态", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(connectionState, style = MaterialTheme.typography.bodyLarge)
                if (!settings.configured) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "尚未配置 token。保存后 App 会以前台服务保持一条 WSS 订阅。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedButton(onClick = onRestartService, modifier = Modifier.fillMaxWidth()) {
            Text("强制重连")
        }

        val clipboard = LocalClipboardManager.current
        OutlinedButton(
            onClick = {
                AppLog.log("UI", "log copied (${AppLog.size()} lines)")
                clipboard.setText(AnnotatedString(AppLog.dump()))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("复制日志（${AppLog.size()} 条）")
        }

        Text(
            "AgentPing v1 · 只读推送。任何字段解析失败的消息会按纯文本展示。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
