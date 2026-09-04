package dev.booking.core.availability

import java.time.Instant
import java.util.UUID

/**
 * Availability search (UC4).  Thin by design: the window rule is the only
 * decision the application makes, because everything else about what is bookable
 * is computed in the database from rules, exceptions and occupancy.
 */
class AvailabilityService(
    private val query: AvailabilityQuery,
) {

    fun search(
        providerRef: UUID,
        serviceRef: UUID,
        from: Instant,
        to: Instant,
        resourceRef: UUID?,
    ): AvailabilityOutcome {
        AvailabilityWindow.validate(from, to)?.let {
            return AvailabilityOutcome.WindowRejected(it)
        }
        return AvailabilityOutcome.Found(
            query.search(providerRef, serviceRef, from, to, resourceRef),
        )
    }
}
