package io.dilfi5h.agentping.ui

import io.dilfi5h.agentping.data.MessageEntity

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
