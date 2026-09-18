package io.dilfi5h.agentping.ui

import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.data.StateKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal sealed interface TimelineEntry {
    val stableKey: String
    val latestTime: Long

    data class SessionGroup(
        val sessionId: String,
        val messages: List<MessageEntity>,
    ) : TimelineEntry {
        val latest: MessageEntity get() = messages.first()
        override val stableKey: String = "session:$sessionId"
        override val latestTime: Long get() = latest.time
    }

    data class SingleMessage(
        val message: MessageEntity,
    ) : TimelineEntry {
        override val stableKey: String = "message:${message.id}"
        override val latestTime: Long = message.time
    }
}

internal fun aggregateTimeline(messages: List<MessageEntity>): List<TimelineEntry> {
    val sessionMessages = linkedMapOf<String, MutableList<MessageEntity>>()
    val entries = mutableListOf<TimelineEntry>()

    messages.forEach { message ->
        val sessionId = message.session?.takeIf { it.isNotBlank() }
        if (sessionId == null) {
            entries += TimelineEntry.SingleMessage(message)
        } else {
            sessionMessages.getOrPut(sessionId) { mutableListOf() } += message
        }
    }

    sessionMessages.forEach { (sessionId, groupedMessages) ->
        entries += TimelineEntry.SessionGroup(
            sessionId = sessionId,
            messages = groupedMessages.sortedWith(
                compareByDescending<MessageEntity> { it.time }.thenByDescending { it.id }
            ),
        )
    }

    return entries.sortedWith(
        compareByDescending<TimelineEntry> { it.latestTime }.thenBy { it.stableKey }
    )
}

internal fun formatDuration(ms: Long): String {
    if (ms < 1000L) return "<1s"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return buildString {
        if (hours > 0) append("${hours}h")
        if (minutes > 0) {
            if (isNotEmpty()) append(" ")
            append("${minutes}m")
        }
        if (seconds > 0 && hours == 0L) {
            if (isNotEmpty()) append(" ")
            append("${seconds}s")
        }
        if (isEmpty()) append("${minutes}m")
    }
}

internal fun shouldShowDuration(state: StateKind, dur: Long?): Boolean =
    dur != null && dur > 0 && (state == StateKind.FINISHED || state == StateKind.FAILED)

internal fun sessionSpanMs(messages: List<MessageEntity>): Long? {
    if (messages.size < 2) return null
    val times = messages.map { it.time }.filter { it > 0L }
    if (times.size < 2) return null
    val span = times.max() - times.min()
    return span.takeIf { it > 0L }
}

internal fun chronological(messages: List<MessageEntity>): List<MessageEntity> =
    messages.sortedWith(compareBy<MessageEntity> { it.time }.thenBy { it.id })

internal fun gapsFromPrevious(messagesChrono: List<MessageEntity>): List<Long?> =
    messagesChrono.mapIndexed { index, message ->
        if (index == 0) null
        else (message.time - messagesChrono[index - 1].time).coerceAtLeast(0L)
    }

/** Card timestamps and "Today" / "Earlier" use UTC+8 so multi-server clocks stay unambiguous. */
internal val DISPLAY_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** Still "now" if waiting/started, or last activity within this window. */
internal const val ACTIVE_WINDOW_MS = 2L * 3600_000L

internal data class TimelineFilter(
    val query: String = "",
    val host: String? = null,
    val agent: String? = null,
) {
    val hasConstraints: Boolean
        get() = query.isNotBlank() || host != null || agent != null
}

internal enum class TimelineSection { Active, Today, Earlier }

internal data class TimelineSections(
    val active: List<TimelineEntry> = emptyList(),
    val today: List<TimelineEntry> = emptyList(),
    val earlier: List<TimelineEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = active.isEmpty() && today.isEmpty() && earlier.isEmpty()
}

internal fun TimelineEntry.displayedMessage(): MessageEntity = when (this) {
    is TimelineEntry.SessionGroup -> latest
    is TimelineEntry.SingleMessage -> message
}

internal fun distinctHosts(messages: List<MessageEntity>): List<String> =
    distinctField(messages) { it.host }

internal fun distinctAgents(messages: List<MessageEntity>): List<String> =
    distinctField(messages) { it.agent }

internal fun filterEntries(entries: List<TimelineEntry>, filter: TimelineFilter): List<TimelineEntry> {
    if (!filter.hasConstraints) return entries
    val query = filter.query.trim()
    return entries.filter { entry ->
        val messages = entry.entryMessages()
        val hostOk = filter.host == null || messages.any { it.host == filter.host }
        val agentOk = filter.agent == null || messages.any { it.agent == filter.agent }
        hostOk && agentOk && (query.isEmpty() || entry.matchesQuery(query))
    }
}

internal fun sectionEntries(
    entries: List<TimelineEntry>,
    nowMs: Long,
    zone: ZoneId = DISPLAY_ZONE,
    activeWindowMs: Long = ACTIVE_WINDOW_MS,
): TimelineSections {
    val active = mutableListOf<TimelineEntry>()
    val today = mutableListOf<TimelineEntry>()
    val earlier = mutableListOf<TimelineEntry>()
    val todayDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    entries.forEach { entry ->
        when (sectionOf(entry, nowMs, todayDate, zone, activeWindowMs)) {
            TimelineSection.Active -> active += entry
            TimelineSection.Today -> today += entry
            TimelineSection.Earlier -> earlier += entry
        }
    }
    return TimelineSections(active, today, earlier)
}

internal fun sectionOf(
    entry: TimelineEntry,
    nowMs: Long,
    zone: ZoneId = DISPLAY_ZONE,
    activeWindowMs: Long = ACTIVE_WINDOW_MS,
): TimelineSection {
    val todayDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return sectionOf(entry, nowMs, todayDate, zone, activeWindowMs)
}

private fun sectionOf(
    entry: TimelineEntry,
    nowMs: Long,
    todayDate: LocalDate,
    zone: ZoneId,
    activeWindowMs: Long,
): TimelineSection {
    val latest = entry.displayedMessage()
    val inProgress = latest.stateKind == StateKind.WAITING || latest.stateKind == StateKind.STARTED
    val age = nowMs - entry.latestTime
    if (inProgress || age <= activeWindowMs) return TimelineSection.Active
    val latestDate = Instant.ofEpochMilli(entry.latestTime).atZone(zone).toLocalDate()
    return if (latestDate == todayDate) TimelineSection.Today else TimelineSection.Earlier
}

private fun TimelineEntry.matchesQuery(query: String): Boolean {
    if (this is TimelineEntry.SessionGroup && sessionId.contains(query, ignoreCase = true)) return true
    return entryMessages().any { it.matchesQuery(query) }
}

private fun TimelineEntry.entryMessages(): List<MessageEntity> = when (this) {
    is TimelineEntry.SessionGroup -> messages
    is TimelineEntry.SingleMessage -> listOf(message)
}

private fun MessageEntity.matchesQuery(query: String): Boolean =
    id.contains(query, ignoreCase = true) ||
        (title?.contains(query, ignoreCase = true) == true) ||
        (host?.contains(query, ignoreCase = true) == true) ||
        (agent?.contains(query, ignoreCase = true) == true) ||
        (state?.contains(query, ignoreCase = true) == true) ||
        stateKind.label.contains(query, ignoreCase = true) ||
        (task?.contains(query, ignoreCase = true) == true) ||
        (detail?.contains(query, ignoreCase = true) == true) ||
        (session?.contains(query, ignoreCase = true) == true) ||
        (raw?.contains(query, ignoreCase = true) == true) ||
        topic.contains(query, ignoreCase = true)

private fun distinctField(messages: List<MessageEntity>, pick: (MessageEntity) -> String?): List<String> =
    messages.mapNotNull { pick(it)?.takeIf { value -> value.isNotBlank() } }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
