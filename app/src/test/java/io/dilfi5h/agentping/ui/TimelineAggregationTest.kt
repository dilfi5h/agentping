package io.dilfi5h.agentping.ui

import io.dilfi5h.agentping.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAggregationTest {
    @Test
    fun `groups messages with the same session and sorts them newest first`() {
        val entries = aggregateTimeline(
            listOf(
                message(id = "older", time = 100, session = "session-a"),
                message(id = "newer", time = 300, session = "session-a"),
            )
        )

        assertEquals(1, entries.size)
        val group = entries.single() as TimelineEntry.SessionGroup
        assertEquals("session-a", group.sessionId)
        assertEquals(listOf("newer", "older"), group.messages.map { it.id })
        assertEquals(300, group.latestTime)
    }

    @Test
    fun `keeps different sessions in separate groups`() {
        val entries = aggregateTimeline(
            listOf(
                message(id = "a", time = 100, session = "session-a"),
                message(id = "b", time = 200, session = "session-b"),
            )
        )

        assertEquals(2, entries.size)
        assertEquals(
            listOf("session-b", "session-a"),
            entries.map { (it as TimelineEntry.SessionGroup).sessionId },
        )
    }

    @Test
    fun `keeps missing and blank sessions as independent messages`() {
        val entries = aggregateTimeline(
            listOf(
                message(id = "missing", time = 300, session = null),
                message(id = "blank", time = 200, session = "   "),
                message(id = "session", time = 100, session = "session-a"),
            )
        )

        assertEquals(3, entries.size)
        assertTrue(entries[0] is TimelineEntry.SingleMessage)
        assertTrue(entries[1] is TimelineEntry.SingleMessage)
        assertTrue(entries[2] is TimelineEntry.SessionGroup)
        assertEquals("missing", (entries[0] as TimelineEntry.SingleMessage).message.id)
        assertEquals("blank", (entries[1] as TimelineEntry.SingleMessage).message.id)
    }

    @Test
    fun `sorts groups and independent messages together by latest time`() {
        val entries = aggregateTimeline(
            listOf(
                message(id = "group-old", time = 100, session = "session-a"),
                message(id = "single", time = 250, session = null),
                message(id = "group-new", time = 300, session = "session-a"),
                message(id = "other", time = 200, session = "session-b"),
            )
        )

        assertEquals(
            listOf("session:session-a", "message:single", "session:session-b"),
            entries.map { it.stableKey },
        )
    }

    private fun message(
        id: String,
        time: Long,
        session: String?,
    ) = MessageEntity(
        id = id,
        time = time,
        topic = "agentping-all",
        title = null,
        raw = null,
        agent = "zcode",
        host = "test-host",
        state = "finished",
        task = "test task",
        detail = null,
        session = session,
        ts = null,
        dur = null,
    )
}
