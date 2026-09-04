package dev.booking.api

import dev.booking.core.listing.BookingSummary
import dev.booking.core.listing.ListingService
import dev.booking.core.listing.Page
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The read endpoints of UC10. */
@RestController
class ListingController(
    private val listings: ListingService,
    private val currentActor: CurrentActor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/v1/bookings")
    fun myBookings(
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<Any> {
        val subject = currentActor.require().subject
        val bookings = listings.myBookings(subject, Page(limit, offset))
        log.info("customer bookings listed count={}", bookings.size)
        return ResponseEntity.ok(BookingListResponse(bookings.map { it.toResponse() }))
    }

    @GetMapping("/v1/providers/{providerRef}/bookings")
    fun providerCalendar(
        @PathVariable providerRef: UUID,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<Any> {
        val subject = currentActor.require().subject
        val bookings = listings.providerCalendar(providerRef, subject, from, to, Page(limit, offset))
        // R18: an unknown provider and one the caller does not administer answer
        // identically.
        if (bookings == null) {
            log.info("provider calendar rejected reason=not-found-or-not-yours")
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("NOT_FOUND", "R18", "no such provider"))
        }
        log.info("provider calendar listed providerRef={} count={}", providerRef, bookings.size)
        return ResponseEntity.ok(BookingListResponse(bookings.map { it.toResponse() }))
    }
}

private fun BookingSummary.toResponse() = BookingSummaryResponse(
    bookingRef = bookingRef,
    state = state,
    holdReason = holdReason,
    startsAt = startsAt,
    endsAt = endsAt,
    providerRef = providerRef,
    serviceRef = serviceRef,
    resourceRef = resourceRef,
    providerTimezone = providerTimezone,
)
