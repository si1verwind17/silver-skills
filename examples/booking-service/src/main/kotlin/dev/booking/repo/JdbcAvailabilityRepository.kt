package dev.booking.repo

import dev.booking.core.availability.AvailabilityQuery
import dev.booking.core.availability.AvailableSlot
import dev.booking.core.availability.ResourceSelector
import dev.booking.sys.SqlSource
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Availability is computed, never stored (data-design.md section 1.2), so both
 * queries here read through database functions rather than any materialised table.
 */
@Repository
class JdbcAvailabilityRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : AvailabilityQuery, ResourceSelector {

    private val searchSql = sql.load("availability/search_availability")
    private val selectResourceSql = sql.load("availability/select_resource")

    override fun search(
        providerRef: UUID,
        serviceRef: UUID,
        from: Instant,
        to: Instant,
        resourceRef: UUID?,
    ): List<AvailableSlot> =
        jdbc.sql(searchSql)
            .param("providerRef", providerRef)
            .param("serviceRef", serviceRef)
            .param("from", from.atOffset(ZoneOffset.UTC))
            .param("to", to.atOffset(ZoneOffset.UTC))
            .param("resourceRef", resourceRef)
            .query { rs, _ ->
                AvailableSlot(
                    resourceRef = rs.getObject("resource_ref", UUID::class.java),
                    startsAt = rs.getObject("starts_at", java.time.OffsetDateTime::class.java).toInstant(),
                    endsAt = rs.getObject("ends_at", java.time.OffsetDateTime::class.java).toInstant(),
                    remainingCapacity = rs.getInt("remaining_capacity"),
                )
            }
            .list()

    override fun selectFor(serviceId: Long, startsAt: Instant): Long? =
        jdbc.sql(selectResourceSql)
            .param("serviceId", serviceId)
            .param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
            .query(Long::class.java)
            .optional()
            .orElse(null)
}
