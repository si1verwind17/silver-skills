package dev.booking.api

import dev.booking.core.listing.ListingService
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Service-to-service endpoints.  Never routed through public ingress.
 *
 * This exists solely because PD17 keeps PII out of events: a notification service
 * receives identifiers and must call back for the address it needs
 * (architecture-design.md AQ3). It is the only path by which contact details
 * leave this service, which is what makes that decision auditable.
 */
@RestController
class InternalController(
    private val listings: ListingService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/internal/v1/bookings/{bookingRef}/contacts")
    fun contacts(@PathVariable bookingRef: UUID): ResponseEntity<Any> {
        val contacts = listings.contactsFor(bookingRef)
        if (contacts == null) {
            log.info("contact lookup missed bookingRef={}", bookingRef)
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("NOT_FOUND", "UC10", "no such booking"))
        }
        // The identifier is logged, never the details themselves (NF5).
        log.info("contact lookup served bookingRef={} erased={}", bookingRef, contacts.erased)
        return ResponseEntity.ok(
            ContactsResponse(
                customerRef = contacts.customerRef,
                displayName = contacts.displayName,
                email = contacts.email,
                phone = contacts.phone,
                erased = contacts.erased,
                cancellationReason = contacts.cancellationReason,
            ),
        )
    }
}
