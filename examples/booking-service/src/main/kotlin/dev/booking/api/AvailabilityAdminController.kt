package dev.booking.api

import dev.booking.core.management.AvailabilityExceptionDraft
import dev.booking.core.management.AvailabilityManagementService
import dev.booking.core.management.AvailabilityRuleDraft
import dev.booking.core.management.ExceptionType
import dev.booking.core.management.ManagementOutcome
import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Publishing availability (UC2, UC3). */
@RestController
@RequestMapping("/v1/providers/{providerRef}")
class AvailabilityAdminController(
    private val management: AvailabilityManagementService,
    private val currentActor: CurrentActor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/availability-rules")
    fun publishRule(
        @PathVariable providerRef: UUID,
        @Valid @RequestBody request: AvailabilityRuleRequest,
    ): ResponseEntity<Any> = respond(
        management.publishRule(
            providerRef,
            currentActor.require().subject,
            AvailabilityRuleDraft(
                resourceRef = requireNotNull(request.resourceRef),
                dayOfWeek = requireNotNull(request.dayOfWeek),
                startTime = requireNotNull(request.startTime),
                endTime = requireNotNull(request.endTime),
                effectiveFrom = requireNotNull(request.effectiveFrom),
                effectiveUntil = request.effectiveUntil,
            ),
        ),
        HttpStatus.CREATED,
    )

    /**
     * Ending a rule, expressed as a DELETE because that is what a provider means —
     * but it sets `effective_until` rather than removing the row (R20), and the
     * response says which bookings it stranded.
     */
    @DeleteMapping("/availability-rules/{ruleRef}")
    fun endRule(
        @PathVariable providerRef: UUID,
        @PathVariable ruleRef: UUID,
        @RequestBody(required = false) request: EndRuleRequest?,
    ): ResponseEntity<Any> = respond(
        management.endRule(
            providerRef,
            currentActor.require().subject,
            ruleRef,
            request?.effectiveUntil ?: LocalDate.now(java.time.ZoneOffset.UTC),
        ),
        HttpStatus.OK,
    )

    @PostMapping("/availability-exceptions")
    fun addException(
        @PathVariable providerRef: UUID,
        @Valid @RequestBody request: AvailabilityExceptionRequest,
    ): ResponseEntity<Any> {
        val type = ExceptionType.entries.firstOrNull { it.name == request.type }
            ?: return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse("BAD_REQUEST", "UC3", "type must be BLOCK or OPEN"))
        return respond(
            management.addException(
                providerRef,
                currentActor.require().subject,
                AvailabilityExceptionDraft(
                    resourceRef = requireNotNull(request.resourceRef),
                    type = type,
                    startsAt = requireNotNull(request.startsAt),
                    endsAt = requireNotNull(request.endsAt),
                    reason = request.reason,
                ),
            ),
            HttpStatus.CREATED,
        )
    }

    private fun respond(outcome: ManagementOutcome, success: HttpStatus): ResponseEntity<Any> =
        when (outcome) {
            is ManagementOutcome.Applied -> {
                log.info(
                    "availability updated ref={} conflicts={}",
                    outcome.ref, outcome.conflicts.size,
                )
                ResponseEntity.status(success).body(
                    AvailabilityChangeResponse(
                        ref = outcome.ref,
                        conflicts = outcome.conflicts.map {
                            ConflictResponse(it.bookingRef, it.startsAt, it.endsAt, it.state, it.resourceRef)
                        },
                    ),
                )
            }

            ManagementOutcome.NotFound -> {
                log.info("availability update rejected reason=not-found-or-not-yours")
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse("NOT_FOUND", "R18", "no such provider or resource"))
            }
        }
}
