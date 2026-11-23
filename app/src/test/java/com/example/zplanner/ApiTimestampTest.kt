package com.example.zplanner

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiTimestampTest {
    @Test
    fun includesMillisecondsRequiredByWorker() {
        assertEquals("2026-11-20T00:00:00.000Z", Instant.parse("2026-11-20T00:00:00Z").toApiTimestamp())
        assertEquals("2026-11-20T00:00:00.123Z", Instant.parse("2026-11-20T00:00:00.123Z").toApiTimestamp())
    }
}
