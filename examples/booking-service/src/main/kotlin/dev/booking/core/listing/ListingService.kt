package dev.booking.core.listing

import dev.booking.core.booking.CustomerDirectory
import dev.booking.core.booking.ProviderAdminDirectory
import java.time.Instant
import java.util.UUID

/**
 * Reads (UC10).
 *
 * Every listing is scoped by an identity the caller cannot influence: a customer
 * sees their own bookings because the query keys on the id resolved from their
 * token, and a provider calendar is served only to an administrator of that
 * provider (R18).
 */
class ListingService(
    private val listings: BookingListings,
    private val customers: CustomerDirectory,
    private val providerAdmins: ProviderAdminDirectory,
    private val providers: ProviderLookup,
) {

    /** A caller with no profile yet simply has no bookings — not an error. */
    fun myBookings(subject: String, page: Page): List<BookingSummary> =
        customers.findIdBySubject(subject)
            ?.let { listings.forCustomer(it, page) }
            ?: emptyList()

    fun providerCalendar(
        providerRef: UUID,
        subject: String,
        from: Instant,
        to: Instant,
        page: Page,
    ): List<BookingSummary>? {
        val providerId = providers.findId(providerRef) ?: return null
        if (providerId !in providerAdmins.administeredProviderIds(subject)) return null
        return listings.forProvider(providerId, from, to, page)
    }

    /**
     * Contact resolution for a consuming service (AQ3).  Not customer-facing and
     * not exposed through public ingress; the caller is another service.
     */
    fun contactsFor(bookingRef: UUID): BookingContacts? = listings.contactsFor(bookingRef)
}

interface ProviderLookup {
    fun findId(providerRef: UUID): Long?
}
