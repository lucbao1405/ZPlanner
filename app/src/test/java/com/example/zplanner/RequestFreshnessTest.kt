package com.example.zplanner

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for stale response guards used by task and event loads. */
class RequestFreshnessTest {
    @Test
    fun olderSameDayRequestCannotReplaceNewerRequest() {
        val guard = FreshnessGuard()
        val older = guard.begin(date = "2026-11-20", account = "session-a")
        val newer = guard.begin(date = "2026-11-20", account = "session-a")

        assertTrue(guard.accepts(newer, date = "2026-11-20", account = "session-a"))
        assertFalse(guard.accepts(older, date = "2026-11-20", account = "session-a"))
    }

    @Test
    fun responseFromPreviousAccountIsRejectedEvenForSameDateAndWeek() {
        val guard = FreshnessGuard()
        val taskRequest = guard.begin(date = "2026-11-20", account = "session-a")
        val eventRequest = guard.begin(date = "week:2026-11-16", account = "session-a")

        guard.changeAccount()
        val taskForB = guard.begin(date = "2026-11-20", account = "session-b")
        val eventForB = guard.begin(date = "week:2026-11-16", account = "session-b")

        assertFalse(guard.accepts(taskRequest, date = "2026-11-20", account = "session-b"))
        assertFalse(guard.accepts(eventRequest, date = "week:2026-11-16", account = "session-b"))
        assertTrue(guard.accepts(taskForB, date = "2026-11-20", account = "session-b"))
        assertTrue(guard.accepts(eventForB, date = "week:2026-11-16", account = "session-b"))
    }

    @Test
    fun responseForPreviouslySelectedDateIsRejectedAfterDateChange() {
        val guard = FreshnessGuard()
        val oldDate = guard.begin(date = "2026-11-20", account = "session-a")
        guard.begin(date = "2026-11-21", account = "session-a")

        assertFalse(guard.accepts(oldDate, date = "2026-11-21", account = "session-a"))
    }
}

/** Small deterministic model of the generation, sequence, date, and account checks. */
private class FreshnessGuard {
    private val accountGeneration = AtomicLong(0)
    private val latestRequestId = mutableMapOf<String, Long>()

    data class Ticket(val accountGeneration: Long, val requestId: Long, val stream: String, val date: String, val account: String)

    fun begin(date: String, account: String): Ticket {
        val stream = if (date.startsWith("week:")) "events" else "tasks"
        val requestId = (latestRequestId[stream] ?: 0L) + 1L
        latestRequestId[stream] = requestId
        return Ticket(accountGeneration.get(), requestId, stream, date, account)
    }

    fun changeAccount() {
        accountGeneration.incrementAndGet()
        latestRequestId.clear()
    }

    fun accepts(ticket: Ticket, date: String, account: String) =
        ticket.accountGeneration == accountGeneration.get() &&
            ticket.requestId == latestRequestId[ticket.stream] && ticket.date == date && ticket.account == account
}