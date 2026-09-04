package dev.booking.core.booking

import java.util.UUID

/**
 * Ports the booking service depends on.  They are declared here, in the core, and
 * implemented in the repo layer — so business code depends on the abstraction and
 * SQL never appears on an interface.
 */
interface BookingRepository {
    /** Runs `fn_create_booking` — one transaction, one round trip. */
    fun create(command: CreateBookingCommand): BookingOutcome
}

interface BookingCatalog {
    /**
     * Resolves a public service reference to its internal id and the provider
     * policy the gate decision needs.  Whether a given resource is actually
     * *eligible* for it is decided by the database (BK008), not here.
     */
    fun findServiceContext(serviceRef: UUID): ServiceContext?

    /** Resolves a public resource reference to its internal id. */
    fun findResourceId(resourceRef: UUID): Long?
}

interface BookingLookup {
    /** Resolves a public booking reference to its identity and ownership. */
    fun findIdentity(ref: java.util.UUID): BookingIdentity?
}

interface BookingTransitions {
    /** Runs `fn_transition_booking` — the one path every state change takes. */
    fun transition(command: TransitionCommand): BookingOutcome

    /** Runs `fn_reschedule_booking` — release and take in one transaction (R9). */
    fun reschedule(command: RescheduleCommand): BookingOutcome
}

interface ProviderAdminDirectory {
    /** The providers this IdP subject administers, for R18 scoping. */
    fun administeredProviderIds(idpSubject: String): Set<Long>
}

interface CustomerDirectory {
    /**
     * The customer id for an already-known subject, or null.  Distinct from
     * [findOrCreate] so that merely authorizing a request cannot create a profile
     * as a side effect.
     */
    fun findIdBySubject(idpSubject: String): Long?

    /** Resolves a public customer reference, for provider-initiated bookings. */
    fun findIdByRef(customerRef: UUID): Long?

    /**
     * Maps a verified IdP subject onto this service's own customer profile,
     * creating it on first use.  Registration is out of scope (LD3), so the
     * profile is provisioned from the token rather than from a sign-up flow.
     */
    fun findOrCreate(idpSubject: String, contact: CustomerContact): Long
}

/** Everything `fn_transition_booking` needs. */
data class TransitionCommand(
    val bookingId: Long,
    val bookingRef: java.util.UUID,
    val targetState: BookingState,
    val actorType: ActorType,
    val actorSubject: String,
    val reason: String?,
    val gate: HoldReason?,
    val completionSource: CompletionSource?,
    val now: java.time.Instant,
    val eventId: java.util.UUID,
)

/** Everything `fn_reschedule_booking` needs. */
data class RescheduleCommand(
    val bookingId: Long,
    val bookingRef: java.util.UUID,
    val newResourceId: Long?,
    val newStartsAt: java.time.Instant,
    val actorType: ActorType,
    val actorSubject: String,
    val gate: HoldReason?,
    val now: java.time.Instant,
    val eventId: java.util.UUID,
)

/** Whether attendance was recorded by a person or by the auto-complete sweep. */
enum class CompletionSource { PROVIDER, SYSTEM }
