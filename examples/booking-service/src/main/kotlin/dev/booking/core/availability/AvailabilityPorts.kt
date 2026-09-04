package dev.booking.core.availability

import java.time.Instant
import java.util.UUID

interface AvailabilityQuery {
    /**
     * Runs `fn_search_availability`.  Every returned start already satisfies R1,
     * R3 and R31 at the moment of the call — the application filters nothing.
     */
    fun search(
        providerRef: UUID,
        serviceRef: UUID,
        from: Instant,
        to: Instant,
        resourceRef: UUID?,
    ): List<AvailableSlot>
}

interface ResourceSelector {
    /**
     * Chooses a resource when the customer expressed no preference (PD14).
     *
     * Deterministic by requirement: fewest capacity-holding sessions that local
     * day, then lowest resource id. Deterministic matters because an arbitrary
     * choice would make the same request testable only by accident.
     */
    fun selectFor(serviceId: Long, startsAt: Instant): Long?
}
