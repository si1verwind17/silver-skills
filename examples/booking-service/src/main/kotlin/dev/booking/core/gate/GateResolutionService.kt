package dev.booking.core.gate

import dev.booking.core.booking.ActorType
import dev.booking.core.booking.BookingLookup
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.BookingTransitions
import dev.booking.core.booking.HoldReason
import dev.booking.core.booking.TransitionCommand
import dev.booking.sys.IdGenerator
import java.time.Clock

/**
 * Applies a gate resolution consumed from the broker (UC14).
 *
 * This is the inbound half of the saga, and the branch that matters is the
 * unhappy one: a resolution that arrives after the hold has lapsed must **not**
 * resurrect the booking.  It changes nothing and publishes the booking's actual
 * state instead, so the sender can compensate — refund a payment taken against a
 * hold that had already expired (R28).
 *
 * Today no producer exists (LD10).  The mechanism is built and tested anyway,
 * because it is the part LD16 promised would be additive.
 */
class GateResolutionService(
    private val inbox: InboxRepository,
    private val lookup: BookingLookup,
    private val transitions: BookingTransitions,
    private val rejections: GateRejections,
    private val clock: Clock,
    private val ids: IdGenerator,
) {

    fun handle(resolution: GateResolution): GateHandling {
        val inboxId = inbox.claim(resolution.messageId, "GateResolution", resolution.rawPayload)
            ?: return GateHandling.DUPLICATE

        val handling = apply(resolution)
        inbox.markProcessed(inboxId, handling, clock.instant())
        return handling
    }

    private fun apply(resolution: GateResolution): GateHandling {
        val booking = lookup.findIdentity(resolution.bookingRef)
            ?: return GateHandling.UNKNOWN_BOOKING

        val gate = HoldReason.entries.firstOrNull { it.name == resolution.holdReason }
            ?: return reject(booking.bookingId)

        val target = when (resolution.outcome) {
            GateOutcome.RESOLVED -> BookingState.CONFIRMED
            GateOutcome.REJECTED -> BookingState.DECLINED
        }

        val outcome = transitions.transition(
            TransitionCommand(
                bookingId = booking.bookingId,
                bookingRef = booking.ref,
                targetState = target,
                actorType = ActorType.PROVIDER,
                actorSubject = resolution.messageId,
                reason = resolution.reason,
                gate = gate,
                completionSource = null,
                now = clock.instant(),
                eventId = ids.newId(),
            ),
        )

        return when (outcome) {
            is BookingOutcome.Transitioned -> GateHandling.APPLIED
            // The booking is no longer held on this gate — expired, cancelled or
            // already resolved.  Nothing changes and the sender is told (R28).
            is BookingOutcome.Rejected -> reject(booking.bookingId)
            BookingOutcome.NotFound -> GateHandling.UNKNOWN_BOOKING
            is BookingOutcome.Created -> error("a transition cannot create a booking")
        }
    }

    private fun reject(bookingId: Long): GateHandling {
        rejections.recordRejection(bookingId, ids.newId())
        return GateHandling.REJECTED
    }
}
