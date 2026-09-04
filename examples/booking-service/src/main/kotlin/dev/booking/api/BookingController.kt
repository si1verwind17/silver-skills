package dev.booking.api

import dev.booking.core.booking.Attendance
import dev.booking.core.booking.BookingLifecycleService
import dev.booking.core.booking.BookingRequest
import dev.booking.core.booking.BookingService
import dev.booking.core.booking.CustomerContact
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The booking endpoints of architecture-design.md section 5.
 *
 * Each method does three things and nothing else: map wire types to domain types,
 * call one service method, hand the outcome to the responder.  No rule is
 * evaluated here and no outcome is interpreted here.
 */
@RestController
@RequestMapping("/v1/bookings")
class BookingController(
    private val bookings: BookingService,
    private val lifecycle: BookingLifecycleService,
    private val currentActor: CurrentActor,
    private val responder: BookingOutcomeResponder,
) {

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateBookingRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<Any> {
        val actor = currentActor.require()
        return responder.respond(
            bookings.book(
                BookingRequest(
                    serviceRef = requireNotNull(request.serviceRef),
                    resourceRef = request.resourceRef,
                    startsAt = requireNotNull(request.startsAt),
                    actorSubject = actor.subject,
                    customerSubject = actor.subject,
                    customerContact = actor.contact.merge(request),
                    idempotencyKey = idempotencyKey,
                    onBehalfOfCustomerRef = request.onBehalfOfCustomerRef,
                ),
            ),
        )
    }

    @PostMapping("/{bookingRef}/approve")
    fun approve(@PathVariable bookingRef: UUID): ResponseEntity<Any> =
        responder.respond(lifecycle.approve(bookingRef, currentActor.require().subject))

    @PostMapping("/{bookingRef}/decline")
    fun decline(
        @PathVariable bookingRef: UUID,
        @RequestBody(required = false) request: DeclineRequest?,
    ): ResponseEntity<Any> =
        responder.respond(
            lifecycle.decline(bookingRef, currentActor.require().subject, request?.reason),
        )

    @PostMapping("/{bookingRef}/cancel")
    fun cancel(
        @PathVariable bookingRef: UUID,
        @RequestBody(required = false) request: CancelRequest?,
    ): ResponseEntity<Any> =
        responder.respond(
            lifecycle.cancel(bookingRef, currentActor.require().subject, request?.reason),
        )

    @PostMapping("/{bookingRef}/reschedule")
    fun reschedule(
        @PathVariable bookingRef: UUID,
        @Valid @RequestBody request: RescheduleRequest,
    ): ResponseEntity<Any> =
        responder.respond(
            lifecycle.reschedule(
                ref = bookingRef,
                subject = currentActor.require().subject,
                newStartsAt = requireNotNull(request.startsAt),
                newResourceId = null,
            ),
        )

    @PostMapping("/{bookingRef}/attendance")
    fun recordAttendance(
        @PathVariable bookingRef: UUID,
        @Valid @RequestBody request: AttendanceRequest,
    ): ResponseEntity<Any> {
        val attendance = Attendance.entries
            .firstOrNull { it.name == request.attendance }
            ?: return responder.badAttendance(request.attendance)
        return responder.respond(
            lifecycle.recordAttendance(bookingRef, currentActor.require().subject, attendance),
        )
    }
}

/**
 * Token claims win where present; the request body fills the gaps.  Keeps contact
 * details out of events entirely (PD17) while still letting a caller supply what
 * the IdP does not carry.
 */
private fun CustomerContact.merge(request: CreateBookingRequest) = CustomerContact(
    displayName = displayName ?: request.displayName,
    email = email ?: request.email,
    phone = phone ?: request.phone,
)
