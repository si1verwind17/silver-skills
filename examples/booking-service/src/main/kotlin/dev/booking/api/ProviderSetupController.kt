package dev.booking.api

import dev.booking.core.booking.ConfirmationMode
import dev.booking.core.management.ProviderDraft
import dev.booking.core.management.ProviderSetupService
import dev.booking.core.management.ResourceDraft
import dev.booking.core.management.ServiceDraft
import dev.booking.core.management.SetupOutcome
import jakarta.validation.Valid
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Provider onboarding (UC1). */
@RestController
class ProviderSetupController(
    private val setup: ProviderSetupService,
    private val currentActor: CurrentActor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/v1/providers")
    fun createProvider(@Valid @RequestBody request: ProviderRequest): ResponseEntity<Any> {
        val mode = ConfirmationMode.entries.firstOrNull { it.name == request.confirmationMode }
            ?: return badValue("confirmationMode must be INSTANT or APPROVAL")
        return respond(
            setup.createProvider(
                currentActor.require().subject,
                ProviderDraft(
                    name = requireNotNull(request.name),
                    timezone = requireNotNull(request.timezone),
                    confirmationMode = mode,
                    minLeadMinutes = request.minLeadMinutes,
                    bookingHorizonDays = request.bookingHorizonDays,
                    cancellationWindowMinutes = request.cancellationWindowMinutes,
                    approvalHoldTtlMinutes = request.approvalHoldTtlMinutes,
                    autoCompleteGraceDays = request.autoCompleteGraceDays,
                ),
            ),
        )
    }

    @PostMapping("/v1/providers/{providerRef}/resources")
    fun addResource(
        @PathVariable providerRef: UUID,
        @Valid @RequestBody request: ResourceRequest,
    ): ResponseEntity<Any> = respond(
        setup.addResource(
            providerRef,
            currentActor.require().subject,
            ResourceDraft(requireNotNull(request.name), request.linkedSubject),
        ),
    )

    @PostMapping("/v1/providers/{providerRef}/services")
    fun addService(
        @PathVariable providerRef: UUID,
        @Valid @RequestBody request: ServiceRequest,
    ): ResponseEntity<Any> = respond(
        setup.addService(
            providerRef,
            currentActor.require().subject,
            ServiceDraft(
                name = requireNotNull(request.name),
                durationMinutes = requireNotNull(request.durationMinutes),
                capacity = request.capacity,
                bufferBeforeMinutes = request.bufferBeforeMinutes,
                bufferAfterMinutes = request.bufferAfterMinutes,
                slotStepMinutes = request.slotStepMinutes,
            ),
        ),
    )

    @PostMapping("/v1/providers/{providerRef}/services/{serviceRef}/resources/{resourceRef}")
    fun makeEligible(
        @PathVariable providerRef: UUID,
        @PathVariable serviceRef: UUID,
        @PathVariable resourceRef: UUID,
    ): ResponseEntity<Any> =
        respond(setup.makeEligible(providerRef, currentActor.require().subject, serviceRef, resourceRef))

    private fun badValue(message: String): ResponseEntity<Any> {
        log.info("provider setup rejected reason=bad-value detail={}", message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("BAD_REQUEST", "UC1", message))
    }

    private fun respond(outcome: SetupOutcome): ResponseEntity<Any> = when (outcome) {
        is SetupOutcome.Created -> {
            log.info("provider setup created ref={}", outcome.ref)
            ResponseEntity.status(HttpStatus.CREATED).body(CreatedRefResponse(outcome.ref))
        }

        SetupOutcome.Linked -> {
            log.info("provider setup linked service and resource")
            ResponseEntity.noContent().build()
        }

        SetupOutcome.NotFound -> {
            log.info("provider setup rejected reason=not-found-or-not-yours")
            ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("NOT_FOUND", "R18", "no such provider, service or resource"))
        }
    }
}
