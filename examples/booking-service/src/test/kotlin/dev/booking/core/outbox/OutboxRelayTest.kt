package dev.booking.core.outbox

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tier 2 — the relay's behaviour with a stubbed broker.  A broker failure is not
 * an error path here but the normal way an outage is absorbed, so it is tested as
 * carefully as the success path.
 */
class OutboxRelayTest {

    private val now = Instant.parse("2026-09-01T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun event(id: Long, attempts: Int = 0) = PendingEvent(
        outboxEventId = id,
        eventId = UUID.randomUUID(),
        bookingRef = UUID.randomUUID(),
        eventType = "BookingConfirmed",
        schemaVersion = 1,
        payload = "{}",
        occurredAt = now,
        attemptCount = attempts,
    )

    private class FakeOutbox(private val due: List<PendingEvent>) : OutboxRepository {
        val dispatched = mutableListOf<Long>()
        val failures = mutableListOf<Triple<Long, String, Instant>>()
        override fun claimDue(now: Instant, limit: Int) = due
        override fun markDispatched(outboxEventId: Long, now: Instant) {
            dispatched += outboxEventId
        }
        override fun recordFailure(outboxEventId: Long, error: String, nextAttemptAt: Instant, now: Instant) {
            failures += Triple(outboxEventId, error, nextAttemptAt)
        }
    }

    private class FakePublisher(private val failOn: Set<Long> = emptySet()) : EventPublisher {
        val published = mutableListOf<Long>()
        override fun publish(event: PendingEvent) {
            if (event.outboxEventId in failOn) throw IllegalStateException("broker unavailable")
            published += event.outboxEventId
        }
    }

    private fun relay(outbox: OutboxRepository, publisher: EventPublisher) =
        OutboxRelay(outbox, publisher, clock, batchSize = 10)

    @Test
    fun `a successful publish marks the row dispatched`() {
        val outbox = FakeOutbox(listOf(event(1), event(2)))
        val publisher = FakePublisher()

        val report = relay(outbox, publisher).dispatchDue()

        assertEquals(DispatchReport(published = 2, failed = 0), report)
        assertEquals(listOf(1L, 2L), publisher.published)
        assertEquals(listOf(1L, 2L), outbox.dispatched)
    }

    @Test
    fun `a broker failure leaves the row undispatched and schedules a retry`() {
        val outbox = FakeOutbox(listOf(event(1, attempts = 2)))
        val report = relay(outbox, FakePublisher(failOn = setOf(1L))).dispatchDue()

        assertEquals(DispatchReport(published = 0, failed = 1), report)
        assertTrue(outbox.dispatched.isEmpty(), "an unacknowledged event must stay undispatched")
        val (id, error, nextAttempt) = outbox.failures.single()
        assertEquals(1L, id)
        assertEquals("broker unavailable", error)
        assertEquals(RetryBackoff.nextAttemptAt(now, 2), nextAttempt)
    }

    @Test
    fun `one failure does not stop the rest of the batch`() {
        val outbox = FakeOutbox(listOf(event(1), event(2), event(3)))
        val publisher = FakePublisher(failOn = setOf(2L))

        val report = relay(outbox, publisher).dispatchDue()

        assertEquals(DispatchReport(published = 2, failed = 1), report)
        assertEquals(listOf(1L, 3L), publisher.published)
        assertEquals(listOf(1L, 3L), outbox.dispatched)
    }

    @Test
    fun `an empty backlog does nothing at all`() {
        val outbox = FakeOutbox(emptyList())
        val publisher = FakePublisher()

        assertEquals(DispatchReport(0, 0), relay(outbox, publisher).dispatchDue())
        assertTrue(publisher.published.isEmpty() && outbox.dispatched.isEmpty())
    }
}
