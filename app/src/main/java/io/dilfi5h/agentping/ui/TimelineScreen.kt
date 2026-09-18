package io.dilfi5h.agentping.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    StateKind.UNKNOWN -> StateUnknown
}

/** 卡片时间一律显示东八区绝对时间（多服务器/多时区无歧义）。 */
private val CST_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.of("Asia/Shanghai"))

fun absTime(ms: Long): String = CST_FORMAT.format(Instant.ofEpochMilli(ms))

private fun sessionCountLabel(count: Int, spanMs: Long?): String = buildString {
    append("$count 条消息")
    spanMs?.let { append("  ·  会话跨度 ${formatDuration(it)}") }
}

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
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    val timelineEntries = remember(messages) { aggregateTimeline(messages) }
    val selectedMessages = remember(messages, selectedSessionId) {
        selectedSessionId?.let { sessionId ->
            messages
                .filter { it.session == sessionId }
                .sortedWith(compareByDescending<MessageEntity> { it.time }.thenByDescending { it.id })
        }
    }

    // 已连接或任何终态（失败/中断/关闭）都收起指示器，避免 401 时空转
    LaunchedEffect(connectionState) {
        if (connectionState != "连接中" && connectionState.isNotBlank()) refreshing = false
    }
    LaunchedEffect(selectedSessionId, selectedMessages?.size) {
        if (selectedSessionId != null && selectedMessages.isNullOrEmpty()) selectedSessionId = null
    }
    BackHandler(enabled = selectedSessionId != null) { selectedSessionId = null }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; onRefresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            messages.isEmpty() -> EmptyTimeline(connectionState)
            selectedSessionId != null && !selectedMessages.isNullOrEmpty() -> SessionTimeline(
                sessionId = selectedSessionId!!,
                messages = selectedMessages,
                onBack = { selectedSessionId = null },
                onDelete = onDelete,
                onOpen = { detailOf = it },
            )
            else -> AggregatedTimeline(
                entries = timelineEntries,
                onOpenSession = { selectedSessionId = it },
                onDelete = onDelete,
                onOpenMessage = { detailOf = it },
            )
        }
    }
    detailOf?.let { MessageDetailSheet(it) { detailOf = null } }
}

@Composable
private fun EmptyTimeline(connectionState: String) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (connectionState == "已连接") "还没有消息\n\n下拉刷新可拉取最近 12 小时的历史\n或从服务器发一条测试消息"
            else "未连接（$connectionState）\n\n请到「设置」检查配置",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .height(480.dp),
        )
    }
}

@Composable
private fun AggregatedTimeline(
    entries: List<TimelineEntry>,
    onOpenSession: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenMessage: (MessageEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.stableKey }) { entry ->
            when (entry) {
                is TimelineEntry.SessionGroup -> SessionGroupCard(entry, onOpenSession)
                is TimelineEntry.SingleMessage -> SwipeDeleteCard(
                    entry.message,
                    onDelete,
                    onOpen = { onOpenMessage(entry.message) },
                )
            }
        }
    }
}

@Composable
private fun SessionTimeline(
    sessionId: String,
    messages: List<MessageEntity>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (MessageEntity) -> Unit,
) {
    val chrono = remember(messages) { chronological(messages) }
    val gaps = remember(chrono) { gapsFromPrevious(chrono) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "session-header") {
            SessionHeader(sessionId, chrono.size, sessionSpanMs(chrono), onBack)
        }
        items(chrono.size, key = { chrono[it].id }) { index ->
            TimelineNode(
                message = chrono[index],
                gapFromPrevious = gaps[index],
                isFirst = index == 0,
                isLast = index == chrono.lastIndex,
                onDelete = onDelete,
                onOpen = { onOpen(chrono[index]) },
            )
        }
    }
}

@Composable
private fun SessionHeader(sessionId: String, count: Int, spanMs: Long?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                sessionId,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sessionCountLabel(count, spanMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionGroupCard(group: TimelineEntry.SessionGroup, onOpen: (String) -> Unit) {
    val latest = group.latest
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(group.sessionId) },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        group.sessionId,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${sessionCountLabel(group.messages.size, sessionSpanMs(group.messages))}  ·  ${latest.stateKind.label}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = stateColor(latest.stateKind),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        absTime(group.latestTime),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${latest.host ?: "?"}  ·  ${latest.agent ?: "shell"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            latest.task?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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
            if (shouldShowDuration(m.stateKind, m.dur)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "耗时 ${formatDuration(m.dur!!)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            DetailField("消息 id", m.id, mono = true)
            DetailField("主机", m.host ?: "-")
            DetailField("Agent", m.agent ?: "-")
            DetailField("状态", "${m.stateKind.label}（raw: ${m.state ?: "-"}）")
            DetailField("任务", m.task?.takeIf { it.isNotBlank() } ?: "-")
            DetailField("详情", m.detail?.takeIf { it.isNotBlank() } ?: "-", mono = true)
            DetailField("ntfy 时间", if (m.time > 0L) absTime(m.time) else "-")
            m.ts?.takeIf { it > 0L }?.let {
                DetailField("reporter 时钟", "${absTime(it)}（仅展示，不用于排序）")
            }
            if (shouldShowDuration(m.stateKind, m.dur)) {
                DetailField("任务耗时", formatDuration(m.dur!!))
            }
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

@Composable
private fun TimelineNode(
    message: MessageEntity,
    gapFromPrevious: Long?,
    isFirst: Boolean,
    isLast: Boolean,
    onDelete: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.width(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(if (isFirst) 8.dp else 16.dp)
                    .background(
                        if (isFirst) Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant,
                    )
            )
            Box(
                Modifier
                    .size(10.dp)
                    .background(stateColor(message.stateKind), CircleShape)
            )
            if (!isLast) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            gapFromPrevious?.let {
                Text(
                    "间隔 ${formatDuration(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            SwipeDeleteCard(message, onDelete, onOpen)
        }
    }
}

/** 详情页「复制全部」用的纯文本。 */
private fun MessageEntity.detailPlainText(): String = buildString {
    appendLine("状态: ${stateKind.label} (raw: ${state ?: "-"})")
    appendLine("ntfy 时间: ${if (time > 0L) absTime(time) else "-"}")
    ts?.takeIf { it > 0L }?.let { appendLine("reporter 时钟: ${absTime(it)}（仅展示，不用于排序）") }
    if (shouldShowDuration(stateKind, dur)) appendLine("任务耗时: ${formatDuration(dur!!)}")
    appendLine("消息 id: $id")
    appendLine("主机: ${host ?: "-"}")
    appendLine("Agent: ${agent ?: "-"}")
    task?.takeIf { it.isNotBlank() }?.let { appendLine("任务: $it") }
    detail?.takeIf { it.isNotBlank() }?.let { appendLine("详情: $it") }
    appendLine("会话: ${session ?: "-"}")
    appendLine("Topic: $topic")
    raw?.let { appendLine("原始: $it") }
}
