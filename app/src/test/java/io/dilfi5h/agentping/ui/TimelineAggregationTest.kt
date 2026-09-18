package io.dilfi5h.agentping.ui

import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.data.StateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `session span uses ntfy time and ignores dur and ts`() {
        val messages = listOf(
            message(id = "b", time = 300, session = "session-a", dur = 9_000_000, ts = 1),
            message(id = "a", time = 100, session = "session-a", dur = 1, ts = 999),
        )
        assertEquals(200L, sessionSpanMs(messages))
        assertEquals(200L, sessionSpanMs(messages.reversed()))
    }

    @Test
    fun `session span is null for a single message or empty list`() {
        assertNull(sessionSpanMs(emptyList()))
        assertNull(sessionSpanMs(listOf(message(id = "a", time = 100, session = "session-a"))))
    }

    @Test
    fun `same-second span is hidden and formatted as under one second`() {
        val sameSecond = listOf(
            message(id = "a", time = 1000, session = "session-a"),
            message(id = "b", time = 1000, session = "session-a"),
        )
        assertNull(sessionSpanMs(sameSecond))
        assertEquals("<1s", formatDuration(0))
        assertEquals("<1s", formatDuration(500))
    }

    @Test
    fun `chronological order is oldest first and does not change latest`() {
        val grouped = aggregateTimeline(
            listOf(
                message(id = "newer", time = 300, session = "session-a"),
                message(id = "older", time = 100, session = "session-a"),
            )
        ).single() as TimelineEntry.SessionGroup
        assertEquals("newer", grouped.latest.id)
        assertEquals(listOf("older", "newer"), chronological(grouped.messages).map { it.id })
    }

    @Test
    fun `gaps start with null then use ntfy time difference`() {
        val chrono = chronological(
            listOf(
                message(id = "a", time = 100, session = "session-a"),
                message(id = "b", time = 100, session = "session-a"),
                message(id = "c", time = 250, session = "session-a"),
            )
        )
        assertEquals(listOf(null, 0L, 150L), gapsFromPrevious(chrono))
    }

    @Test
    fun `formatDuration uses compact units`() {
        assertEquals("1s", formatDuration(1000))
        assertEquals("1m 1s", formatDuration(61_000))
        assertEquals("1h", formatDuration(3_600_000))
        assertEquals("1h 1m", formatDuration(3_661_000))
    }

    @Test
    fun `task duration is only shown for finished or failed with positive dur`() {
        assertTrue(shouldShowDuration(StateKind.FINISHED, 2000))
        assertTrue(shouldShowDuration(StateKind.FAILED, 1))
        assertFalse(shouldShowDuration(StateKind.WAITING, 2000))
        assertFalse(shouldShowDuration(StateKind.STARTED, 2000))
        assertFalse(shouldShowDuration(StateKind.FINISHED, null))
        assertFalse(shouldShowDuration(StateKind.FINISHED, 0))
    }

    private fun message(
        id: String,
        time: Long,
        session: String?,
        ts: Long? = null,
        dur: Long? = null,
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
        ts = ts,
        dur = dur,
    )
}
