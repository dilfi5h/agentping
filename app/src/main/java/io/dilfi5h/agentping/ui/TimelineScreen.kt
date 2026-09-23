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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.data.StateKind
import java.time.Instant
import java.time.format.DateTimeFormatter

fun stateColor(s: StateKind): Color = when (s) {
    StateKind.STARTED -> StateStarted
    StateKind.FINISHED -> StateFinished
    StateKind.FAILED -> StateFailed
    StateKind.WAITING -> StateWaiting
    StateKind.UNKNOWN -> StateUnknown
}

/** Cards always show UTC+8 absolute time (unambiguous across multiple servers/time zones). */
private val CST_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .withZone(DISPLAY_ZONE)

fun absTime(ms: Long): String = CST_FORMAT.format(Instant.ofEpochMilli(ms))

private fun sessionCountLabel(count: Int, spanMs: Long?): String = buildString {
    append("$count messages")
    spanMs?.let { append("  ·  session span ${formatDuration(it)}") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    messages: List<MessageEntity>,
    connectionState: String,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    openSessionId: String? = null,
    onOpenSessionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var refreshing by remember { mutableStateOf(false) }
    var detailOf by remember { mutableStateOf<MessageEntity?>(null) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var hostFilter by remember { mutableStateOf<String?>(null) }
    var agentFilter by remember { mutableStateOf<String?>(null) }
    val timelineEntries = remember(messages) { aggregateTimeline(messages) }
    val hosts = remember(messages) { distinctHosts(messages) }
    val agents = remember(messages) { distinctAgents(messages) }
    val filter = remember(query, hostFilter, agentFilter) {
        TimelineFilter(query = query, host = hostFilter, agent = agentFilter)
    }
    val filteredEntries = remember(timelineEntries, filter) { filterEntries(timelineEntries, filter) }
    val sections = remember(filteredEntries) {
        sectionEntries(filteredEntries, System.currentTimeMillis())
    }
    val selectedMessages = remember(messages, selectedSessionId) {
        selectedSessionId?.let { sessionId ->
            messages
                .filter { it.session == sessionId }
                .sortedWith(compareByDescending<MessageEntity> { it.time }.thenByDescending { it.id })
        }
    }
    val showFilters = messages.isNotEmpty() && selectedSessionId == null

    // Collapse the indicator on connect success or any terminal state (failed/lost/closed) so a 401 doesn't spin forever
    LaunchedEffect(connectionState) {
        if (connectionState != "Connecting" && connectionState.isNotBlank()) refreshing = false
    }
    LaunchedEffect(openSessionId, messages) {
        val sessionId = resolveOpenSession(openSessionId, messages)
        when {
            sessionId != null -> {
                selectedSessionId = sessionId
                onOpenSessionConsumed()
            }
            shouldDropOpenSession(openSessionId, messages) -> onOpenSessionConsumed()
        }
    }
    LaunchedEffect(selectedSessionId, selectedMessages?.size, openSessionId) {
        if (selectedSessionId != null && selectedMessages.isNullOrEmpty() && openSessionId == null) {
            selectedSessionId = null
        }
    }
    LaunchedEffect(hosts, hostFilter) {
        if (hostFilter != null && hostFilter !in hosts) hostFilter = null
    }
    LaunchedEffect(agents, agentFilter) {
        if (agentFilter != null && agentFilter !in agents) agentFilter = null
    }
    BackHandler(enabled = selectedSessionId != null) { selectedSessionId = null }

    Column(modifier.fillMaxSize()) {
        if (showFilters) {
            TimelineFilters(
                query = query,
                onQueryChange = { query = it },
                hosts = hosts,
                agents = agents,
                hostFilter = hostFilter,
                agentFilter = agentFilter,
                onHostFilter = { hostFilter = it },
                onAgentFilter = { agentFilter = it },
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; onRefresh() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                filteredEntries.isEmpty() -> NoMatches(filter.hasConstraints)
                else -> AggregatedTimeline(
                    sections = sections,
                    onOpenSession = { selectedSessionId = it },
                    onDelete = onDelete,
                    onOpenMessage = { detailOf = it },
                )
            }
        }
    }
    detailOf?.let { MessageDetailSheet(it) { detailOf = null } }
}

@Composable
private fun TimelineFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    hosts: List<String>,
    agents: List<String>,
    hostFilter: String?,
    agentFilter: String?,
    onHostFilter: (String?) -> Unit,
    onAgentFilter: (String?) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search") },
            placeholder = { Text("Task, session, host, detail") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        if (hosts.size > 1) {
            FilterChipRow(
                options = hosts,
                selected = hostFilter,
                onSelect = onHostFilter,
            )
        }
        if (agents.size > 1) {
            FilterChipRow(
                options = agents,
                selected = agentFilter,
                onSelect = onAgentFilter,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it }) { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(if (selected == option) null else option) },
                label = { Text(option) },
            )
        }
    }
}

@Composable
private fun NoMatches(hasConstraints: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (hasConstraints) "No matching sessions\n\nClear search or chips to see the full timeline"
            else "No messages yet",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .height(240.dp),
        )
    }
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
            if (connectionState == "Connected") "No messages yet\n\nPull to refresh to fetch the last 12 hours of history\nor send a test message from the server"
            else "Not connected ($connectionState)\n\nCheck your configuration in \"Settings\"",
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
    sections: TimelineSections,
    onOpenSession: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenMessage: (MessageEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        timelineSection("Active", sections.active, onOpenSession, onDelete, onOpenMessage)
        timelineSection("Today", sections.today, onOpenSession, onDelete, onOpenMessage)
        timelineSection("Earlier", sections.earlier, onOpenSession, onDelete, onOpenMessage)
    }
}

private fun LazyListScope.timelineSection(
    title: String,
    entries: List<TimelineEntry>,
    onOpenSession: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenMessage: (MessageEntity) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "section-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
    }
    items(entries, key = { it.stableKey }) { entry ->
        when (entry) {
            is TimelineEntry.SessionGroup -> SessionGroupCard(entry, onOpenSession)
            is TimelineEntry.SingleMessage -> SwipeDeleteCard(
                entry.message,
                onDelete,
                onOpen = { onOpenMessage(entry.message) },
                showDuration = true,
            )
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
    val gaps = remember(chrono) { turnGapsFromPrevious(chrono) }
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
                gapsFromPrevious = gaps[index],
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
    showDuration: Boolean = true,
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
        enableDismissFromStartToEnd = false, // swipe-left (right-to-left) only
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
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        MessageCard(m, onOpen, showDuration)
    }
}

@Composable
private fun MessageCard(m: MessageEntity, onOpen: () -> Unit, showDuration: Boolean = true) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
    ) {
        Column(Modifier.padding(12.dp)) {
            // Two header rows: row 1 = host + time badge; row 2 = agent · state, so the three don't crowd into one line
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
                // Time badge: owns its width, never compressed
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
            if (showDuration && shouldShowDuration(m.stateKind, m.dur)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Duration ${formatDuration(m.dur!!)}",
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
            DetailField("Message id", m.id, mono = true)
            DetailField("Host", m.host ?: "-")
            DetailField("Agent", m.agent ?: "-")
            DetailField("State", "${m.stateKind.label} (raw: ${m.state ?: "-"})")
            DetailField("Task", m.task?.takeIf { it.isNotBlank() } ?: "-")
            DetailField("Detail", m.detail?.takeIf { it.isNotBlank() } ?: "-", mono = true)
            DetailField("ntfy time", if (m.time > 0L) absTime(m.time) else "-")
            m.ts?.takeIf { it > 0L }?.let {
                DetailField("Reporter clock", "${absTime(it)} (display only, not used for sorting)")
            }
            if (shouldShowDuration(m.stateKind, m.dur)) {
                DetailField("Task duration", formatDuration(m.dur!!))
            }
            DetailField("Session", m.session ?: "-")
            DetailField("Topic", m.topic)
            DetailField("Raw message", m.raw ?: "-", mono = true)

            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(m.detailPlainText()))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy all and close") }
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
    gapsFromPrevious: List<TurnGap>,
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
            gapsFromPrevious.forEach { gap ->
                Text(
                    when (gap) {
                        is TurnGap.Idle -> "${formatDuration(gap.ms)} later"
                        is TurnGap.Work -> "Gap ${formatDuration(gap.ms)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            SwipeDeleteCard(message, onDelete, onOpen, showDuration = false)
        }
    }
}

/** Plain text for the detail sheet's "Copy all" button. */
private fun MessageEntity.detailPlainText(): String = buildString {
    appendLine("State: ${stateKind.label} (raw: ${state ?: "-"})")
    appendLine("ntfy time: ${if (time > 0L) absTime(time) else "-"}")
    ts?.takeIf { it > 0L }?.let { appendLine("Reporter clock: ${absTime(it)} (display only, not used for sorting)") }
    if (shouldShowDuration(stateKind, dur)) appendLine("Task duration: ${formatDuration(dur!!)}")
    appendLine("Message id: $id")
    appendLine("Host: ${host ?: "-"}")
    appendLine("Agent: ${agent ?: "-"}")
    task?.takeIf { it.isNotBlank() }?.let { appendLine("Task: $it") }
    detail?.takeIf { it.isNotBlank() }?.let { appendLine("Detail: $it") }
    appendLine("Session: ${session ?: "-"}")
    appendLine("Topic: $topic")
    raw?.let { appendLine("Raw: $it") }
}
