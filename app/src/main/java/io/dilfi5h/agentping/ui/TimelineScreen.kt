package io.dilfi5h.agentping.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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
                    SwipeDeleteCard(m, onDelete)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteCard(m: MessageEntity, onDelete: (String) -> Unit) {
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
        MessageCard(m)
    }
}

@Composable
private fun MessageCard(m: MessageEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // 左侧三要素（可截断） + 右侧时间徽章（独占，永不压缩）
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 三要素整体作为一个 weight 子项：Compose 先测非加权子项（时间徽章），
                // 这里拿到的是剩余宽度，空间不足时在内部截断而不是挤掉徽章。
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // host / agent 用 fill = false 的 weight：不被拉伸，空间不足时按份额 ellipsis
                    Text(
                        m.host ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("  ·  ", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1)
                    Text(
                        m.agent ?: "shell",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("  ·  ", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1)
                    // 状态词短且语义重要：非加权，优先保证完整显示
                    Text(
                        m.stateKind.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = stateColor(m.stateKind),
                        maxLines = 1,
                    )
                }
                // 外层 Row 里唯一的非加权子项：先按 intrinsic 宽度测量，永不被左侧挤到零宽
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
