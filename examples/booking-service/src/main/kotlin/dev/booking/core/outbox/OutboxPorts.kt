package dev.booking.core.outbox

import java.time.Instant

interface OutboxRepository {
    /** The oldest undispatched event per booking that is due (R29 ordering). */
    fun claimDue(now: Instant, limit: Int): List<PendingEvent>

    fun markDispatched(outboxEventId: Long, now: Instant)

    fun recordFailure(outboxEventId: Long, error: String, nextAttemptAt: Instant, now: Instant)
}

/**
 * Publishes to the estate's broker.  An interface rather than a Kafka type so the
 * relay is testable without a broker, and so PD15's fallback to another product is
 * a new implementation rather than a rewrite (stack-selection.md section 4).
 */
interface EventPublisher {
    /** Publishes keyed by booking reference.  Throws if the broker did not accept it. */
    fun publish(event: PendingEvent)
}
