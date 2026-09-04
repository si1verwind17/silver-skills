package dev.booking.api

import dev.booking.core.availability.AvailabilityOutcome
import dev.booking.core.availability.AvailabilityService
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Availability search (UC4). */
@RestController
class AvailabilityController(
    private val availability: AvailabilityService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/v1/providers/{providerRef}/availability")
    fun search(
        @PathVariable providerRef: UUID,
        @RequestParam serviceRef: UUID,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(required = false) resourceRef: UUID?,
    ): ResponseEntity<Any> =
        when (val outcome = availability.search(providerRef, serviceRef, from, to, resourceRef)) {
            is AvailabilityOutcome.Found -> {
                log.info(
                    "availability searched providerRef={} serviceRef={} slots={}",
                    providerRef, serviceRef, outcome.slots.size,
                )
                ResponseEntity.ok(
                    AvailabilityResponse(
                        outcome.slots.map {
                            AvailableSlotResponse(
                                it.resourceRef, it.startsAt, it.endsAt, it.remainingCapacity,
                            )
                        },
                    ),
                )
            }

            is AvailabilityOutcome.WindowRejected -> {
                log.info("availability search rejected reason={}", outcome.detail)
                ResponseEntity
                    .status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ErrorResponse("WINDOW", "PD7", outcome.detail))
            }
        }
}
