package dev.booking.core.management

import dev.booking.core.booking.ProviderAdminDirectory
import dev.booking.core.listing.ProviderLookup
import dev.booking.sys.IdGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** A recurring window a provider wants to publish (UC2). */
data class AvailabilityRuleDraft(
    val resourceRef: UUID,
    val dayOfWeek: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
)

/** A one-off override (UC3). */
data class AvailabilityExceptionDraft(
    val resourceRef: UUID,
    val type: ExceptionType,
    val startsAt: Instant,
    val endsAt: Instant,
    val reason: String?,
)

enum class ExceptionType { BLOCK, OPEN }

/** A booking left outside published availability by an edit (R20). */
data class AvailabilityConflict(
    val bookingRef: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val state: String,
    val resourceRef: UUID,
)

sealed interface ManagementOutcome {
    /**
     * The edit was applied.  [conflicts] is R20's obligation, not a warning: the
     * provider is handed every booking now outside published availability, because
     * the service will not cancel them on their behalf.
     */
    data class Applied(val ref: UUID, val conflicts: List<AvailabilityConflict>) : ManagementOutcome

    /** Unknown provider, unknown resource, or a caller who does not administer it. */
    data object NotFound : ManagementOutcome
}

interface AvailabilityAdminRepository {
    fun insertRule(providerId: Long, publicRef: UUID, draft: AvailabilityRuleDraft): Boolean
    fun endRule(providerId: Long, ruleRef: UUID, effectiveUntil: LocalDate, now: Instant): Boolean
    fun insertException(providerId: Long, publicRef: UUID, draft: AvailabilityExceptionDraft): Boolean
    fun conflictsFor(providerId: Long, now: Instant): List<AvailabilityConflict>
}

/**
 * Publishing and withdrawing availability (UC2, UC3).
 *
 * Every mutation answers with the conflicts it created. That is the whole point
 * of R20: withdrawing availability must never silently cancel a booking, so the
 * provider is told precisely what now needs a decision.
 */
class AvailabilityManagementService(
    private val repository: AvailabilityAdminRepository,
    private val providers: ProviderLookup,
    private val providerAdmins: ProviderAdminDirectory,
    private val clock: Clock,
    private val ids: IdGenerator,
) {

    fun publishRule(providerRef: UUID, subject: String, draft: AvailabilityRuleDraft): ManagementOutcome =
        forProvider(providerRef, subject) { providerId ->
            val ref = ids.newId()
            if (repository.insertRule(providerId, ref, draft)) applied(providerId, ref)
            else ManagementOutcome.NotFound
        }

    fun endRule(providerRef: UUID, subject: String, ruleRef: UUID, effectiveUntil: LocalDate): ManagementOutcome =
        forProvider(providerRef, subject) { providerId ->
            if (repository.endRule(providerId, ruleRef, effectiveUntil, clock.instant())) {
                applied(providerId, ruleRef)
            } else {
                ManagementOutcome.NotFound
            }
        }

    fun addException(
        providerRef: UUID,
        subject: String,
        draft: AvailabilityExceptionDraft,
    ): ManagementOutcome =
        forProvider(providerRef, subject) { providerId ->
            val ref = ids.newId()
            if (repository.insertException(providerId, ref, draft)) applied(providerId, ref)
            else ManagementOutcome.NotFound
        }

    private fun applied(providerId: Long, ref: UUID) =
        ManagementOutcome.Applied(ref, repository.conflictsFor(providerId, clock.instant()))

    private fun forProvider(
        providerRef: UUID,
        subject: String,
        action: (Long) -> ManagementOutcome,
    ): ManagementOutcome {
        val providerId = providers.findId(providerRef) ?: return ManagementOutcome.NotFound
        if (providerId !in providerAdmins.administeredProviderIds(subject)) {
            return ManagementOutcome.NotFound
        }
        return action(providerId)
    }
}
