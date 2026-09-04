package dev.booking.core.management

import dev.booking.core.booking.ConfirmationMode
import dev.booking.core.booking.ProviderAdminDirectory
import dev.booking.core.listing.ProviderLookup
import dev.booking.sys.IdGenerator
import java.util.UUID

/** UC1 — the shape of a provider being onboarded. */
data class ProviderDraft(
    val name: String,
    val timezone: String,
    val confirmationMode: ConfirmationMode,
    val minLeadMinutes: Int?,
    val bookingHorizonDays: Int?,
    val cancellationWindowMinutes: Int?,
    val approvalHoldTtlMinutes: Int?,
    val autoCompleteGraceDays: Int?,
)

data class ResourceDraft(val name: String, val linkedSubject: String?)

data class ServiceDraft(
    val name: String,
    val durationMinutes: Int,
    val capacity: Int?,
    val bufferBeforeMinutes: Int?,
    val bufferAfterMinutes: Int?,
    val slotStepMinutes: Int?,
)

sealed interface SetupOutcome {
    data class Created(val ref: UUID) : SetupOutcome
    data object Linked : SetupOutcome
    data object NotFound : SetupOutcome
}

interface ProviderSetupRepository {
    fun insertProvider(publicRef: UUID, draft: ProviderDraft): Long
    fun addAdmin(providerId: Long, idpSubject: String)
    fun insertResource(providerId: Long, publicRef: UUID, draft: ResourceDraft): Long
    fun insertService(providerId: Long, publicRef: UUID, draft: ServiceDraft): Long
    fun linkServiceResource(providerId: Long, serviceRef: UUID, resourceRef: UUID): Boolean
}

/**
 * Onboarding a provider and its bookable things (UC1).
 *
 * Creating a provider is the one action with nobody to authorize against, so the
 * subject who creates it becomes its first administrator. Every later action is
 * scoped by that membership like everything else (R18). Requirements name no
 * back-office actor, so this is an implementation assumption, recorded as one.
 */
class ProviderSetupService(
    private val repository: ProviderSetupRepository,
    private val providers: ProviderLookup,
    private val providerAdmins: ProviderAdminDirectory,
    private val ids: IdGenerator,
) {

    fun createProvider(subject: String, draft: ProviderDraft): SetupOutcome {
        val ref = ids.newId()
        val providerId = repository.insertProvider(ref, draft)
        repository.addAdmin(providerId, subject)
        return SetupOutcome.Created(ref)
    }

    fun addResource(providerRef: UUID, subject: String, draft: ResourceDraft): SetupOutcome =
        forProvider(providerRef, subject) { providerId ->
            val ref = ids.newId()
            repository.insertResource(providerId, ref, draft)
            SetupOutcome.Created(ref)
        }

    fun addService(providerRef: UUID, subject: String, draft: ServiceDraft): SetupOutcome =
        forProvider(providerRef, subject) { providerId ->
            val ref = ids.newId()
            repository.insertService(providerId, ref, draft)
            SetupOutcome.Created(ref)
        }

    fun makeEligible(
        providerRef: UUID,
        subject: String,
        serviceRef: UUID,
        resourceRef: UUID,
    ): SetupOutcome =
        forProvider(providerRef, subject) { providerId ->
            if (repository.linkServiceResource(providerId, serviceRef, resourceRef)) {
                SetupOutcome.Linked
            } else {
                SetupOutcome.NotFound
            }
        }

    private fun forProvider(
        providerRef: UUID,
        subject: String,
        action: (Long) -> SetupOutcome,
    ): SetupOutcome {
        val providerId = providers.findId(providerRef) ?: return SetupOutcome.NotFound
        if (providerId !in providerAdmins.administeredProviderIds(subject)) {
            return SetupOutcome.NotFound
        }
        return action(providerId)
    }
}
