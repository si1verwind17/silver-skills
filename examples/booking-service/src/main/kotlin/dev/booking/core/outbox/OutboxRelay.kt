package dev.booking.core.outbox

import java.time.Clock
import org.slf4j.LoggerFactory

/**
 * Moves committed events out to the broker (UC13).
 *
 * This is the whole of R22's delivery half: the write path already guaranteed the
 * event exists, so the relay never decides *whether* to publish, only *when* it
 * succeeded.  A failure is not an error path here — it is the normal way a broker
 * outage is absorbed, because the next scan picks the row up again.
 */
class OutboxRelay(
    private val outbox: OutboxRepository,
    private val publisher: EventPublisher,
    private val clock: Clock,
    private val batchSize: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatchDue(): DispatchReport {
        val now = clock.instant()
        var published = 0
        var failed = 0

        outbox.claimDue(now, batchSize).forEach { event ->
            try {
                publisher.publish(event)
                outbox.markDispatched(event.outboxEventId, now)
                published++
            } catch (failure: Exception) {
                // Broad on purpose: this is an integration edge, and every failure
                // mode here has the same remedy — leave the row undispatched and
                // try later.  Nothing is swallowed: the reason is persisted on the
                // row and attemptCount drives the NF11 alert.
                failed++
                outbox.recordFailure(
                    outboxEventId = event.outboxEventId,
                    error = failure.message ?: failure::class.qualifiedName.orEmpty(),
                    nextAttemptAt = RetryBackoff.nextAttemptAt(now, event.attemptCount),
                    now = now,
                )
                log.warn(
                    "event dispatch failed eventId={} bookingRef={} attempt={} reason={}",
                    event.eventId, event.bookingRef, event.attemptCount + 1, failure.message,
                )
            }
        }
        return DispatchReport(published, failed)
    }
}
