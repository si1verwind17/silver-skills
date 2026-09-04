package dev.booking.core.gate

import dev.booking.core.booking.BookingIdentity
import dev.booking.core.booking.BookingLookup
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingRule
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.BookingTransitions
import dev.booking.core.booking.ConfirmationMode
import dev.booking.core.booking.RescheduleCommand
import dev.booking.core.booking.TransitionCommand
import dev.booking.core.booking.TransitionedBooking
import dev.booking.sys.IdGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 — UC14's inbound half.  The branch that matters is the late resolution:
 * it must change nothing and tell the sender, so it can compensate (R28).
 */
class GateResolutionServiceTest {

    private val now = Instant.parse("2026-09-01T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val bookingRef = UUID.fromString("00000000-0000-7000-8000-0000000000c1")

    private val identity = BookingIdentity(
        bookingId = 5,
        ref = bookingRef,
        customerId = 10,
        providerId = 20,
        confirmationMode = ConfirmationMode.APPROVAL,
    )

    private class FakeInbox(private val alreadySeen: Boolean = false) : InboxRepository {
        var processed: GateHandling? = null
        override fun claim(messageId: String, messageType: String, payload: String) =
            if (alreadySeen) null else 1L
        override fun markProcessed(inboxMessageId: Long, outcome: GateHandling, now: Instant) {
            processed = outcome
        }
    }

    private class FakeLookup(private val identity: BookingIdentity?) : BookingLookup {
        override fun findIdentity(ref: UUID) = identity
    }

    private class FakeTransitions(private val outcome: BookingOutcome) : BookingTransitions {
        var received: TransitionCommand? = null
        override fun transition(command: TransitionCommand): BookingOutcome {
            received = command
            return outcome
        }
        override fun reschedule(command: RescheduleCommand) = error("not used")
    }

    private class FakeRejections : GateRejections {
        var rejectedBookingId: Long? = null
        override fun recordRejection(bookingId: Long, eventId: UUID) {
            rejectedBookingId = bookingId
        }
    }

    private object FixedIds : IdGenerator {
        override fun newId(): UUID = UUID.fromString("00000000-0000-7000-8000-0000000000ff")
    }

    private fun resolution(outcome: GateOutcome = GateOutcome.RESOLVED, gate: String = "AWAITING_PROVIDER_APPROVAL") =
        GateResolution(
            messageId = "msg-1",
            bookingRef = bookingRef,
            holdReason = gate,
            outcome = outcome,
            reason = null,
            rawPayload = "{}",
        )

    private fun service(
        inbox: InboxRepository,
        lookup: BookingLookup,
        transitions: BookingTransitions,
        rejections: GateRejections,
    ) = GateResolutionService(inbox, lookup, transitions, rejections, clock, FixedIds)

    @Test
    fun `a resolution on a held booking confirms it`() {
        val transitions = FakeTransitions(
            BookingOutcome.Transitioned(TransitionedBooking(bookingRef, BookingState.CONFIRMED, 2)),
        )
        val inbox = FakeInbox()
        val rejections = FakeRejections()

        val handling = service(inbox, FakeLookup(identity), transitions, rejections).handle(resolution())

        assertEquals(GateHandling.APPLIED, handling)
        assertEquals(BookingState.CONFIRMED, transitions.received!!.targetState)
        assertNull(rejections.rejectedBookingId)
        assertEquals(GateHandling.APPLIED, inbox.processed)
    }

    @Test
    fun `a negative resolution declines it`() {
        val transitions = FakeTransitions(
            BookingOutcome.Transitioned(TransitionedBooking(bookingRef, BookingState.DECLINED, 2)),
        )
        service(FakeInbox(), FakeLookup(identity), transitions, FakeRejections())
            .handle(resolution(GateOutcome.REJECTED))

        assertEquals(BookingState.DECLINED, transitions.received!!.targetState)
    }

    @Test
    fun `a resolution arriving after the hold lapsed changes nothing and tells the sender`() {
        // The whole reason R28 exists: the payment succeeded but the slot is gone.
        val transitions = FakeTransitions(
            BookingOutcome.Rejected(BookingRule.FORBIDDEN_TRANSITION, "booking is terminal"),
        )
        val rejections = FakeRejections()
        val inbox = FakeInbox()

        val handling = service(inbox, FakeLookup(identity), transitions, rejections).handle(resolution())

        assertEquals(GateHandling.REJECTED, handling)
        assertEquals(5L, rejections.rejectedBookingId, "the sender must be able to compensate")
        assertEquals(GateHandling.REJECTED, inbox.processed)
    }

    @Test
    fun `a redelivered message is a no-op`() {
        val transitions = FakeTransitions(
            BookingOutcome.Transitioned(TransitionedBooking(bookingRef, BookingState.CONFIRMED, 2)),
        )
        val handling = service(FakeInbox(alreadySeen = true), FakeLookup(identity), transitions, FakeRejections())
            .handle(resolution())

        assertEquals(GateHandling.DUPLICATE, handling)
        assertNull(transitions.received, "R29: a duplicate must not re-apply its effect")
    }

    @Test
    fun `an unknown booking is reported, not applied`() {
        val transitions = FakeTransitions(
            BookingOutcome.Transitioned(TransitionedBooking(bookingRef, BookingState.CONFIRMED, 2)),
        )
        val handling = service(FakeInbox(), FakeLookup(null), transitions, FakeRejections()).handle(resolution())

        assertEquals(GateHandling.UNKNOWN_BOOKING, handling)
        assertNull(transitions.received)
    }

    @Test
    fun `a resolution naming a gate this service does not know is rejected`() {
        val transitions = FakeTransitions(
            BookingOutcome.Transitioned(TransitionedBooking(bookingRef, BookingState.CONFIRMED, 2)),
        )
        val rejections = FakeRejections()

        val handling = service(FakeInbox(), FakeLookup(identity), transitions, rejections)
            .handle(resolution(gate = "AWAITING_SOMETHING_ELSE"))

        assertEquals(GateHandling.REJECTED, handling)
        assertTrue(transitions.received == null, "an unknown gate must not be applied blindly")
        assertEquals(5L, rejections.rejectedBookingId)
    }
}
