package dev.booking.core.outbox

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One row of the transactional outbox, ready to publish (R22, UC13). */
data class PendingEvent(
    val outboxEventId: Long,
    val eventId: UUID,
    val bookingRef: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
    val occurredAt: Instant,
    val attemptCount: Int,
)

data class DispatchReport(val published: Int, val failed: Int) {
    val attempted: Int get() = published + failed
}

/**
 * How long a failed event waits before the relay tries again.
 *
 * A pure function so the schedule is testable without waiting for it. Capped, so
 * a long outage cannot push an event's next attempt beyond any useful horizon —
 * an uncapped exponential eventually parks an event for days, which looks like
 * data loss to whoever is waiting for it.
 */
object RetryBackoff {

    val initial: Duration = Duration.ofSeconds(1)
    val cap: Duration = Duration.ofMinutes(5)

    fun nextAttemptAt(now: Instant, attemptCount: Int): Instant {
        val exponent = attemptCount.coerceIn(0, 30)
        val delaySeconds = initial.seconds shl exponent
        val delay =
            if (delaySeconds <= 0 || delaySeconds > cap.seconds) cap
            else Duration.ofSeconds(delaySeconds)
        return now.plus(delay)
    }
}
