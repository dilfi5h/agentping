package io.dilfi5h.agentping.ui

import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.data.StateKind

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
