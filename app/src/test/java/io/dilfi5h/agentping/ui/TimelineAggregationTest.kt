package io.dilfi5h.agentping.ui

import io.dilfi5h.agentping.data.MessageEntity
import io.dilfi5h.agentping.data.StateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
    fun `first message has no gap without dur and short wall delta is implied work`() {
        val chrono = chronological(
            listOf(
                message(id = "a", time = 100, session = "session-a"),
                message(id = "b", time = 100, session = "session-a"),
                message(id = "c", time = 250, session = "session-a"),
            )
        )
        assertEquals(emptyList<TurnGap>(), turnGapsFromPrevious(chrono)[0])
        assertEquals(emptyList<TurnGap>(), turnGapsFromPrevious(chrono)[1])
        assertEquals(listOf(TurnGap.Work(150L)), turnGapsFromPrevious(chrono)[2])
    }

    @Test
    fun `first message with dur shows work gap so the opening turn is not silent`() {
        val chrono = chronological(
            listOf(
                message(id = "a", time = 100, session = "s", dur = 5 * 60_000L),
                message(id = "b", time = 8 * 3600_000L, session = "s", dur = 5 * 60_000L),
            )
        )
        val gaps = turnGapsFromPrevious(chrono)
        assertEquals(listOf(TurnGap.Work(5 * 60_000L)), gaps[0])
        assertEquals(
            listOf(TurnGap.Idle(8 * 3600_000L - 100 - 5 * 60_000L), TurnGap.Work(5 * 60_000L)),
            gaps[1],
        )
    }

    @Test
    fun `gap prefers this turn dur over wall-clock idle`() {
        val previous = message(id = "a", time = 0, session = "s")
        val current = message(
            id = "b",
            time = 8 * 3600_000L + 5 * 60_000L,
            session = "s",
            dur = 5 * 60_000L,
        )
        assertEquals(
            listOf(TurnGap.Idle(8 * 3600_000L), TurnGap.Work(5 * 60_000L)),
            turnGapsBetween(previous, current),
        )
    }

    @Test
    fun `short consecutive turns with dur show only work gap`() {
        val previous = message(id = "a", time = 0, session = "s")
        val current = message(id = "b", time = 5 * 60_000L, session = "s", dur = 5 * 60_000L)
        assertEquals(listOf(TurnGap.Work(5 * 60_000L)), turnGapsBetween(previous, current))
    }

    @Test
    fun `long wall delta without dur is idle not work`() {
        val previous = message(id = "a", time = 0, session = "s")
        val current = message(id = "b", time = 8 * 3600_000L + 5 * 60_000L, session = "s", dur = null)
        assertEquals(
            listOf(TurnGap.Idle(8 * 3600_000L + 5 * 60_000L)),
            turnGapsBetween(previous, current),
        )
    }

    @Test
    fun `idle shorter than threshold is hidden`() {
        val previous = message(id = "a", time = 0, session = "s")
        val current = message(id = "b", time = 20 * 60_000L, session = "s", dur = 5 * 60_000L)
        assertEquals(listOf(TurnGap.Work(5 * 60_000L)), turnGapsBetween(previous, current))
    }

    @Test
    fun `missing dur at 30 minutes is still implied work, one millisecond more is idle`() {
        val previous = message(id = "a", time = 0, session = "s")
        assertEquals(
            listOf(TurnGap.Work(MAX_IMPLIED_WORK_MS)),
            turnGapsBetween(previous, message(id = "b", time = MAX_IMPLIED_WORK_MS, session = "s")),
        )
        assertEquals(
            listOf(TurnGap.Idle(MAX_IMPLIED_WORK_MS + 1)),
            turnGapsBetween(previous, message(id = "c", time = MAX_IMPLIED_WORK_MS + 1, session = "s")),
        )
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

    @Test
    fun `waiting and recent finished stay active, older today and earlier split by CST date`() {
        val now = Instant.parse("2026-04-24T10:00:00+08:00").toEpochMilli()
        val entries = listOf(
            TimelineEntry.SingleMessage(message(id = "wait", time = now - 3 * DAY, state = "waiting")),
            TimelineEntry.SingleMessage(message(id = "recent", time = now - 30 * 60_000L, state = "finished")),
            TimelineEntry.SingleMessage(message(id = "today", time = now - 5 * 3600_000L, state = "finished")),
            TimelineEntry.SingleMessage(message(id = "old", time = now - DAY, state = "finished")),
        )
        val sections = sectionEntries(entries, nowMs = now)
        assertEquals(listOf("wait", "recent"), sections.active.map { it.displayedMessage().id })
        assertEquals(listOf("today"), sections.today.map { it.displayedMessage().id })
        assertEquals(listOf("old"), sections.earlier.map { it.displayedMessage().id })
    }

    @Test
    fun `started is active even when older than the window`() {
        val now = 1_000_000L
        val entry = TimelineEntry.SingleMessage(
            message(id = "start", time = now - 9 * 3600_000L, state = "started"),
        )
        assertEquals(TimelineSection.Active, sectionOf(entry, now))
    }

    @Test
    fun `host and agent chips match any message in the session, search looks through the session`() {
        val messages = listOf(
            message(id = "old-task", time = 100, session = "sess-a", host = "deb", agent = "pi", task = "fix login"),
            message(id = "latest", time = 300, session = "sess-a", host = "mac", agent = "opencode", task = "ship apk"),
            message(id = "other", time = 200, session = "sess-b", host = "deb", agent = "pi", task = "rewrite"),
        )
        val entries = aggregateTimeline(messages)
        assertEquals(listOf("deb", "mac"), distinctHosts(messages))
        assertEquals(listOf("opencode", "pi"), distinctAgents(messages))
        assertEquals(
            listOf("sess-a", "sess-b"),
            filterEntries(entries, TimelineFilter(host = "deb")).map { (it as TimelineEntry.SessionGroup).sessionId },
        )
        assertEquals(
            listOf("sess-a"),
            filterEntries(entries, TimelineFilter(host = "mac")).map { (it as TimelineEntry.SessionGroup).sessionId },
        )
        assertEquals(
            listOf("sess-a"),
            filterEntries(entries, TimelineFilter(agent = "opencode")).map { (it as TimelineEntry.SessionGroup).sessionId },
        )
        assertEquals(
            listOf("sess-a"),
            filterEntries(entries, TimelineFilter(query = "fix login")).map { (it as TimelineEntry.SessionGroup).sessionId },
        )
        assertEquals(
            listOf("sess-b"),
            filterEntries(entries, TimelineFilter(host = "deb", query = "rewrite"))
                .map { (it as TimelineEntry.SessionGroup).sessionId },
        )
        assertTrue(filterEntries(entries, TimelineFilter(query = "missing")).isEmpty())
    }

    @Test
    fun `sessionIdOf drops blank and whitespace-only ids`() {
        assertNull(sessionIdOf(null))
        assertNull(sessionIdOf(""))
        assertNull(sessionIdOf("   "))
        assertEquals("sess_fe97e70b", sessionIdOf("sess_fe97e70b"))
    }

    @Test
    fun `notification tap waits until history has loaded then opens or drops`() {
        val loaded = listOf(message(id = "a", time = 1, session = "sess-a"))
        assertNull(resolveOpenSession("sess-a", emptyList()))
        assertFalse(shouldDropOpenSession("sess-a", emptyList()))
        assertEquals("sess-a", resolveOpenSession("sess-a", loaded))
        assertFalse(shouldDropOpenSession("sess-a", loaded))
        assertNull(resolveOpenSession("sess-gone", loaded))
        assertTrue(shouldDropOpenSession("sess-gone", loaded))
        assertFalse(shouldDropOpenSession(null, loaded))
    }

    @Test
    fun `blank query with no chips leaves the list unchanged`() {
        val entries = aggregateTimeline(listOf(message(id = "a", time = 1, session = "s")))
        assertEquals(entries, filterEntries(entries, TimelineFilter()))
        assertEquals(entries, filterEntries(entries, TimelineFilter(query = "   ")))
    }

    private fun message(
        id: String,
        time: Long,
        session: String? = null,
        ts: Long? = null,
        dur: Long? = null,
        host: String? = "test-host",
        agent: String? = "pi",
        state: String? = "finished",
        task: String? = "test task",
        detail: String? = null,
    ) = MessageEntity(
        id = id,
        time = time,
        topic = "agentping-all",
        title = null,
        raw = null,
        agent = agent,
        host = host,
        state = state,
        task = task,
        detail = detail,
        session = session,
        ts = ts,
        dur = dur,
    )

    companion object {
        private const val DAY = 24 * 3600_000L
    }
}
