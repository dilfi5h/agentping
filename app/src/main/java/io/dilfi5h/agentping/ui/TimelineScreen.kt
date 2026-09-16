package io.dilfi5h.agentping.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.data.StateKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun stateColor(s: StateKind): Color = when (s) {
    StateKind.STARTED -> StateStarted
    StateKind.FINISHED -> StateFinished
    StateKind.FAILED -> StateFailed
    StateKind.WAITING -> StateWaiting
}

/** 卡片时间一律显示东八区绝对时间（多服务器/多时区无歧义）。 */
private val CST_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.of("Asia/Shanghai"))

fun absTime(ms: Long): String = CST_FORMAT.format(Instant.ofEpochMilli(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    messages: List<MessageEntity>,
    connectionState: String,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var refreshing by remember { mutableStateOf(false) }
    var detailOf by remember { mutableStateOf<MessageEntity?>(null) }
    // 重连完成（状态回到已连接）即收起刷新指示器
    LaunchedEffect(connectionState) {
        if (connectionState == "已连接") refreshing = false
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; onRefresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (connectionState == "已连接") "还没有消息\n\n下拉刷新可拉取最近 12 小时的历史\n或从服务器发一条测试消息"
                    else "未连接（$connectionState）\n\n请到「设置」检查配置",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    SwipeDeleteCard(m, onDelete, onOpen = { detailOf = m })
                }
            }
        }
    }
    detailOf?.let { MessageDetailSheet(it) { detailOf = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteCard(
    m: MessageEntity,
    onDelete: (String) -> Unit,
    onOpen: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete(m.id)
                true
            } else false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false, // 只支持左滑（从右往左）
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        MessageCard(m, onOpen)
    }
}

@Composable
private fun MessageCard(m: MessageEntity, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
    ) {
        Column(Modifier.padding(12.dp)) {
            // 头部两行：第 1 行 host + 时间徽章；第 2 行 agent · 状态，避免三者挤在一行
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        m.host ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.agent ?: "shell",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text("  ·  ", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1)
                        Text(
                            m.stateKind.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = stateColor(m.stateKind),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 时间徽章：独占宽度，永不压缩
                Surface(shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        absTime(m.time),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            m.task?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            m.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                m.session?.let { "session $it" } ?: "topic ${m.topic}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageDetailSheet(m: MessageEntity, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.stateKind.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = stateColor(m.stateKind),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    absTime(m.time),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DetailField("主机", m.host ?: "-")
            DetailField("Agent", m.agent ?: "-")
            DetailField("状态", "${m.stateKind.label}（raw: ${m.state ?: "-"}）")
            DetailField("任务", m.task?.takeIf { it.isNotBlank() } ?: "-")
            DetailField("详情", m.detail?.takeIf { it.isNotBlank() } ?: "-", mono = true)
            DetailField("会话", m.session ?: "-")
            DetailField("Topic", m.topic)
            DetailField("原始消息", m.raw ?: "-", mono = true)

            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(m.detailPlainText()))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("复制全部并关闭") }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
        }
    }
}

/** 详情页「复制全部」用的纯文本。 */
private fun MessageEntity.detailPlainText(): String = buildString {
    appendLine("状态: ${stateKind.label} (raw: ${state ?: "-"})")
    appendLine("时间: ${absTime(time)}")
    appendLine("主机: ${host ?: "-"}")
    appendLine("Agent: ${agent ?: "-"}")
    task?.takeIf { it.isNotBlank() }?.let { appendLine("任务: $it") }
    detail?.takeIf { it.isNotBlank() }?.let { appendLine("详情: $it") }
    appendLine("会话: ${session ?: "-"}")
    appendLine("Topic: $topic")
    raw?.let { appendLine("原始: $it") }
}
