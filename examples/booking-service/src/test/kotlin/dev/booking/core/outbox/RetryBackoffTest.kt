package dev.booking.core.outbox

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetryBackoffTest {

    private val now = Instant.parse("2026-09-01T08:00:00Z")

    @Test
    fun `the first retry is prompt`() {
        assertEquals(now.plusSeconds(1), RetryBackoff.nextAttemptAt(now, attemptCount = 0))
    }

    @Test
    fun `delay grows with each failure`() {
        val first = RetryBackoff.nextAttemptAt(now, 0)
        val second = RetryBackoff.nextAttemptAt(now, 1)
        val third = RetryBackoff.nextAttemptAt(now, 2)
        assertTrue(first < second && second < third)
    }

    @Test
    fun `delay is capped so an event is never parked out of sight`() {
        // An uncapped exponential would push the next attempt days away after a
        // long outage, which looks like data loss to whoever is waiting for it.
        listOf(10, 20, 31, 60, Int.MAX_VALUE).forEach { attempts ->
            val delay = Duration.between(now, RetryBackoff.nextAttemptAt(now, attempts))
            assertEquals(RetryBackoff.cap, delay, "attempt $attempts must be capped")
        }
    }

    @Test
    fun `a very large attempt count cannot overflow into the past`() {
        assertTrue(
            RetryBackoff.nextAttemptAt(now, Int.MAX_VALUE).isAfter(now),
            "shifting by an unbounded exponent must not wrap around",
        )
    }
}
