package dev.booking.core.booking

import java.time.Instant
import java.util.UUID

/**
 * Booking lifecycle states, exactly those of requirements.md section 4.1.
 *
 * The state machine itself is enforced by the database — `booking_state` carries
 * `is_terminal` and `holds_capacity`, and `booking_transition_rule` holds the
 * permitted moves.  This enum exists so the application can name a state and
 * project it onto the wire, never so it can re-implement the machine.
 */
enum class BookingState {
    HELD, CONFIRMED, CANCELLED, DECLINED, EXPIRED, COMPLETED, NO_SHOW;

    companion object {
        fun ofCode(code: String): BookingState =
            entries.firstOrNull { it.name == code }
                ?: error("unknown booking state '$code' — the database and this enum have diverged")
    }
}

/**
 * The external gate a HELD booking awaits (LD16).  Adding a gate is a row in
 * `hold_reason` plus a constant here; it is never a change to the lifecycle.
 */
enum class HoldReason {
    AWAITING_PROVIDER_APPROVAL,
}

/** Who caused a transition.  Decides whether R4's lead time applies. */
enum class ActorType {
    CUSTOMER, PROVIDER, SYSTEM,
}

/** Whether a provider's bookings need approval (LD2). An input to [GatePolicy]. */
enum class ConfirmationMode {
    INSTANT, APPROVAL,
}

/**
 * A business rule the database refused to let us break.  One constant per `BK*`
 * SQLSTATE raised by ddl/03-functions.sql, so a rejection keeps its identity all
 * the way from the constraint to the HTTP response (NF8).
 */
enum class BookingRule(val sqlState: String, val requirement: String) {
    SESSION_OVERLAP("BK001", "R1/R31"),
    SESSION_FULL("BK002", "R2"),
    OUTSIDE_AVAILABILITY("BK003", "R3"),
    INSIDE_LEAD_TIME("BK004", "R4"),
    BEYOND_HORIZON("BK005", "R5"),
    FORBIDDEN_TRANSITION("BK006", "section 4.1"),
    PAST_CANCELLATION_WINDOW("BK007", "R6"),
    NOT_BOOKABLE("BK008", "R19"),
    ATTENDANCE_TOO_EARLY("BK009", "R12");

    companion object {
        fun ofSqlState(sqlState: String): BookingRule? =
            entries.firstOrNull { it.sqlState == sqlState }
    }
}

/** What the caller asked for, in domain terms rather than wire terms. */
data class BookingRequest(
    val serviceRef: UUID,
    /** Absent when the customer expressed no preference — see PD14. */
    val resourceRef: UUID?,
    val startsAt: Instant,
    val actorSubject: String,
    val customerSubject: String,
    val customerContact: CustomerContact,
    val idempotencyKey: String?,
    /**
     * Set when a provider books for a named customer (UC5).  Honoured only for a
     * caller who administers the service's provider; anyone else supplying it is
     * treated as if the booking did not exist (R18).
     */
    val onBehalfOfCustomerRef: UUID? = null,
)

data class CustomerContact(
    val displayName: String?,
    val email: String?,
    val phone: String?,
)

/** The service's identifiers and the provider policy that decides its gate. */
data class ServiceContext(
    val serviceId: Long,
    val providerId: Long,
    val confirmationMode: ConfirmationMode,
)

/** Everything `fn_create_booking` needs, with time and identifiers already supplied. */
data class CreateBookingCommand(
    val customerId: Long,
    val serviceId: Long,
    val resourceId: Long,
    val startsAt: Instant,
    val gate: HoldReason?,
    val actorType: ActorType,
    val actorSubject: String,
    val idempotencyKey: String?,
    val now: Instant,
    val bookingRef: UUID,
    val eventId: UUID,
)

data class CreatedBooking(
    val ref: UUID,
    val state: BookingState,
    val wasReplay: Boolean,
)

/**
 * The closed set of outcomes a booking attempt can have.  Expected rejections are
 * values, not exceptions — only defects throw.  Exhaustive handling is the API
 * layer's single boundary handler.
 */
sealed interface BookingOutcome {
    data class Created(val booking: CreatedBooking) : BookingOutcome
    data class Transitioned(val booking: TransitionedBooking) : BookingOutcome
    data class Rejected(val rule: BookingRule, val detail: String) : BookingOutcome

    /**
     * The booking does not exist **or** does not belong to the caller.
     *
     * R18 requires those two be indistinguishable: telling one tenant that
     * another tenant's booking exists is itself a leak, so authorization failure
     * is reported as absence rather than as a refusal.
     */
    data object NotFound : BookingOutcome
}

data class TransitionedBooking(
    val ref: UUID,
    val state: BookingState,
    val sequenceNo: Int,
)

/** Identity and ownership of a booking, enough to authorize an action on it. */
data class BookingIdentity(
    val bookingId: Long,
    val ref: UUID,
    val customerId: Long,
    val providerId: Long,
    /** The owning provider's mode, needed to decide a reschedule's gate (R11). */
    val confirmationMode: ConfirmationMode,
)

/** The attendance outcomes a provider may record after a booking ends (UC9). */
enum class Attendance(val targetState: BookingState) {
    COMPLETED(BookingState.COMPLETED),
    NO_SHOW(BookingState.NO_SHOW),
}
