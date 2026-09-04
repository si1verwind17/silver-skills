package dev.booking.core.listing

import java.time.Instant
import java.util.UUID

/** A booking as it appears in a listing (UC10). */
data class BookingSummary(
    val bookingRef: UUID,
    val state: String,
    val holdReason: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val providerRef: UUID,
    val serviceRef: UUID,
    val resourceRef: UUID,
    val providerTimezone: String,
)

/**
 * Contact details for one booking.
 *
 * Returned only by the internal endpoint that exists because events carry
 * identifiers only (PD17). Everything here is PII, which is exactly why it is
 * confined to one type, one query and one endpoint.
 */
data class BookingContacts(
    val customerRef: UUID,
    val displayName: String?,
    val email: String?,
    val phone: String?,
    val erased: Boolean,
    val cancellationReason: String?,
)

data class Page(val limit: Int, val offset: Int) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        require(offset >= 0) { "offset must not be negative" }
    }

    companion object {
        const val MAX_LIMIT = 200
        val first = Page(50, 0)
    }
}

interface BookingListings {
    fun forCustomer(customerId: Long, page: Page): List<BookingSummary>
    fun forProvider(providerId: Long, from: Instant, to: Instant, page: Page): List<BookingSummary>
    fun contactsFor(bookingRef: UUID): BookingContacts?
}
