package dev.booking.core.booking

import dev.booking.sys.IdGenerator
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Every state change a caller can ask for after a booking exists (UC6–UC9).
 *
 * Each operation is a separate named method rather than one `updateBooking` with a
 * mode flag — they authorize identically but mean different things, and a method
 * that needed "and" in its name to be honest would be doing too much.
 *
 * The shape is the same throughout: resolve the booking, work out in what capacity
 * this caller may touch it (R18), then hand a command to the database. No
 * transition legality is judged here — `booking_transition_rule` owns that, so an
 * illegal move is refused by the same authority whatever the caller believes.
 */
class BookingLifecycleService(
    private val lookup: BookingLookup,
    private val transitions: BookingTransitions,
    private val customers: CustomerDirectory,
    private val providerAdmins: ProviderAdminDirectory,
    private val clock: Clock,
    private val ids: IdGenerator,
) {

    /** UC6 — resolve the approval gate positively.  Providers only. */
    fun approve(ref: UUID, subject: String): BookingOutcome =
        onBooking(ref, subject, requiring = ActorType.PROVIDER) { booking, actor ->
            transitions.transition(
                command(booking, BookingState.CONFIRMED, actor, subject)
                    .copy(gate = HoldReason.AWAITING_PROVIDER_APPROVAL),
            )
        }

    /** UC6 — resolve the approval gate negatively.  Providers only. */
    fun decline(ref: UUID, subject: String, reason: String?): BookingOutcome =
        onBooking(ref, subject, requiring = ActorType.PROVIDER) { booking, actor ->
            transitions.transition(
                command(booking, BookingState.DECLINED, actor, subject)
                    .copy(gate = HoldReason.AWAITING_PROVIDER_APPROVAL, reason = reason),
            )
        }

    /**
     * UC7 — either party may cancel.  Which of R6's rules applies is decided by
     * the database from the actor type this resolves to, not from a request field.
     */
    fun cancel(ref: UUID, subject: String, reason: String?): BookingOutcome =
        onBooking(ref, subject) { booking, actor ->
            transitions.transition(
                command(booking, BookingState.CANCELLED, actor, subject).copy(reason = reason),
            )
        }

    /** UC9 — record what actually happened.  Providers only. */
    fun recordAttendance(ref: UUID, subject: String, attendance: Attendance): BookingOutcome =
        onBooking(ref, subject, requiring = ActorType.PROVIDER) { booking, actor ->
            transitions.transition(
                command(booking, attendance.targetState, actor, subject)
                    .copy(completionSource = CompletionSource.PROVIDER),
            )
        }

    /** UC8 — move a booking atomically, keeping its identity. */
    fun reschedule(
        ref: UUID,
        subject: String,
        newStartsAt: Instant,
        newResourceId: Long?,
    ): BookingOutcome =
        onBooking(ref, subject) { booking, actor ->
            transitions.reschedule(
                RescheduleCommand(
                    bookingId = booking.bookingId,
                    bookingRef = booking.ref,
                    newResourceId = newResourceId,
                    newStartsAt = newStartsAt,
                    actorType = actor,
                    actorSubject = subject,
                    gate = GatePolicy.selectRescheduleGate(actor, booking.confirmationMode),
                    now = clock.instant(),
                    eventId = ids.newId(),
                ),
            )
        }

    private fun command(
        booking: BookingIdentity,
        target: BookingState,
        actorType: ActorType,
        subject: String,
    ) = TransitionCommand(
        bookingId = booking.bookingId,
        bookingRef = booking.ref,
        targetState = target,
        actorType = actorType,
        actorSubject = subject,
        reason = null,
        gate = null,
        completionSource = null,
        now = clock.instant(),
        eventId = ids.newId(),
    )

    /**
     * Resolve, authorize, act.  An unknown booking, someone else's booking, and a
     * customer attempting a provider-only action all produce the same answer,
     * because distinguishing them would confirm the existence of another tenant's
     * data (R18).
     */
    private fun onBooking(
        ref: UUID,
        subject: String,
        requiring: ActorType? = null,
        action: (BookingIdentity, ActorType) -> BookingOutcome,
    ): BookingOutcome {
        val booking = lookup.findIdentity(ref) ?: return BookingOutcome.NotFound
        val capacity = BookingAuthorization.capacityOver(
            booking = booking,
            callerCustomerId = customers.findIdBySubject(subject),
            administeredProviderIds = providerAdmins.administeredProviderIds(subject),
        ) ?: return BookingOutcome.NotFound
        if (requiring != null && capacity != requiring) return BookingOutcome.NotFound
        return action(booking, capacity)
    }
}
