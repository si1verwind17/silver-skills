package dev.booking.repo

import dev.booking.core.listing.BookingContacts
import dev.booking.core.listing.BookingListings
import dev.booking.core.listing.BookingSummary
import dev.booking.core.listing.Page
import dev.booking.core.listing.ProviderLookup
import dev.booking.sys.SqlSource
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Read paths for UC10 and the internal contact lookup of AQ3. */
@Repository
class JdbcListingRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : BookingListings, ProviderLookup {

    private val customerBookingsSql = sql.load("listing/customer_bookings")
    private val providerBookingsSql = sql.load("listing/provider_bookings")
    private val contactsSql = sql.load("listing/booking_contacts")
    private val findProviderIdSql = sql.load("management/find_provider_id")

    override fun findId(providerRef: UUID): Long? =
        jdbc.sql(findProviderIdSql)
            .param("providerRef", providerRef)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun forCustomer(customerId: Long, page: Page): List<BookingSummary> =
        jdbc.sql(customerBookingsSql)
            .param("customerId", customerId)
            .param("limit", page.limit)
            .param("offset", page.offset)
            .query { rs, _ -> rs.toSummary() }
            .list()

    override fun forProvider(
        providerId: Long,
        from: Instant,
        to: Instant,
        page: Page,
    ): List<BookingSummary> =
        jdbc.sql(providerBookingsSql)
            .param("providerId", providerId)
            .param("from", from.atOffset(ZoneOffset.UTC))
            .param("to", to.atOffset(ZoneOffset.UTC))
            .param("limit", page.limit)
            .param("offset", page.offset)
            .query { rs, _ -> rs.toSummary() }
            .list()

    override fun contactsFor(bookingRef: UUID): BookingContacts? =
        jdbc.sql(contactsSql)
            .param("bookingRef", bookingRef)
            .query { rs, _ ->
                BookingContacts(
                    customerRef = rs.getObject("customer_ref", UUID::class.java),
                    displayName = rs.getString("display_name"),
                    email = rs.getString("email"),
                    phone = rs.getString("phone"),
                    erased = rs.getObject("erased_at") != null,
                    cancellationReason = rs.getString("cancellation_reason"),
                )
            }
            .optional()
            .orElse(null)

    /** Row shape is identical for both listings, so the mapping exists once. */
    private fun ResultSet.toSummary() = BookingSummary(
        bookingRef = getObject("public_ref", UUID::class.java),
        state = getString("state"),
        holdReason = getString("hold_reason"),
        startsAt = getObject("starts_at", OffsetDateTime::class.java).toInstant(),
        endsAt = getObject("ends_at", OffsetDateTime::class.java).toInstant(),
        providerRef = getObject("provider_ref", UUID::class.java),
        serviceRef = getObject("service_ref", UUID::class.java),
        resourceRef = getObject("resource_ref", UUID::class.java),
        providerTimezone = getString("timezone"),
    )
}
