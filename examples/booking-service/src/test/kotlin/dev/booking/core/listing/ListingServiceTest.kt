package dev.booking.core.listing

import dev.booking.core.booking.CustomerContact
import dev.booking.core.booking.CustomerDirectory
import dev.booking.core.booking.ProviderAdminDirectory
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingServiceTest {

    private val providerRef = UUID.randomUUID()
    private val from = Instant.parse("2026-09-01T00:00:00Z")
    private val to = Instant.parse("2026-09-08T00:00:00Z")

    private class StubListings : BookingListings {
        var customerCalls = 0
        var providerCalls = 0
        override fun forCustomer(customerId: Long, page: Page): List<BookingSummary> {
            customerCalls++
            return emptyList()
        }
        override fun forProvider(providerId: Long, from: Instant, to: Instant, page: Page): List<BookingSummary> {
            providerCalls++
            return emptyList()
        }
        override fun contactsFor(bookingRef: UUID): BookingContacts? = null
    }

    private class StubCustomers(private val id: Long?) : CustomerDirectory {
        override fun findOrCreate(idpSubject: String, contact: CustomerContact) = 1L
        override fun findIdBySubject(idpSubject: String) = id
        override fun findIdByRef(customerRef: UUID) = null
    }

    private class StubAdmins(private val ids: Set<Long>) : ProviderAdminDirectory {
        override fun administeredProviderIds(idpSubject: String) = ids
    }

    private class StubProviders(private val id: Long?) : ProviderLookup {
        override fun findId(providerRef: UUID) = id
    }

    @Test
    fun `a caller with no profile has no bookings rather than an error`() {
        val listings = StubListings()
        val service = ListingService(listings, StubCustomers(null), StubAdmins(emptySet()), StubProviders(1))

        assertTrue(service.myBookings("sub-new", Page.first).isEmpty())
        assertEquals(0, listings.customerCalls, "no profile means nothing to query")
    }

    @Test
    fun `a provider calendar is served only to an administrator of that provider`() {
        val listings = StubListings()
        val allowed = ListingService(listings, StubCustomers(1), StubAdmins(setOf(7)), StubProviders(7))
        val denied = ListingService(listings, StubCustomers(1), StubAdmins(setOf(8)), StubProviders(7))

        assertEquals(emptyList(), allowed.providerCalendar(providerRef, "sub-owner", from, to, Page.first))
        assertNull(
            denied.providerCalendar(providerRef, "sub-other", from, to, Page.first),
            "R18: a provider the caller does not administer answers as absent",
        )
        assertEquals(1, listings.providerCalls, "the denied call must not reach the database")
    }

    @Test
    fun `an unknown provider is indistinguishable from one the caller cannot see`() {
        val service = ListingService(StubListings(), StubCustomers(1), StubAdmins(setOf(7)), StubProviders(null))
        assertNull(service.providerCalendar(providerRef, "sub-owner", from, to, Page.first))
    }

    @Test
    fun `page bounds are validated where the page is constructed`() {
        assertFailsWith<IllegalArgumentException> { Page(0, 0) }
        assertFailsWith<IllegalArgumentException> { Page(Page.MAX_LIMIT + 1, 0) }
        assertFailsWith<IllegalArgumentException> { Page(10, -1) }
    }
}
