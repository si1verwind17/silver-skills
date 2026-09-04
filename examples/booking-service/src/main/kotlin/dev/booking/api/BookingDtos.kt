package dev.booking.api

import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * Wire contract only.  These types never reach the core — [BookingController]
 * maps them to domain types at the boundary, so renaming a JSON field cannot
 * recompile business logic.
 */
data class CreateBookingRequest(
    @field:NotNull val serviceRef: UUID?,
    val resourceRef: UUID? = null,
    @field:NotNull val startsAt: Instant?,
    val displayName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    /** Only honoured for a provider administrator (UC5). */
    val onBehalfOfCustomerRef: UUID? = null,
)

data class BookingResponse(
    val bookingRef: UUID,
    val state: String,
)

/** The result of a state change: the new state and its position in history. */
data class TransitionResponse(
    val bookingRef: UUID,
    val state: String,
    val sequenceNo: Int,
)

data class CancelRequest(val reason: String? = null)

data class DeclineRequest(val reason: String? = null)

data class AttendanceRequest(
    @field:NotNull val attendance: String?,
)

data class RescheduleRequest(
    @field:NotNull val startsAt: Instant?,
    val resourceRef: UUID? = null,
)

data class AvailabilityResponse(
    val slots: List<AvailableSlotResponse>,
)

data class AvailableSlotResponse(
    val resourceRef: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val remainingCapacity: Int,
)

/**
 * Error body for a rejected request.  `rule` names the requirement that refused
 * it, which is what NF8 asks for — a caller should never have to read prose to
 * learn which rule failed.
 */
data class ErrorResponse(
    val code: String,
    val rule: String,
    val message: String,
)

data class BookingListResponse(val bookings: List<BookingSummaryResponse>)

data class BookingSummaryResponse(
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

/** Internal only — the sole route by which PII leaves this service (PD17, AQ3). */
data class ContactsResponse(
    val customerRef: UUID,
    val displayName: String?,
    val email: String?,
    val phone: String?,
    val erased: Boolean,
    val cancellationReason: String?,
)

data class AvailabilityRuleRequest(
    @field:NotNull val resourceRef: UUID?,
    @field:NotNull val dayOfWeek: Int?,
    @field:NotNull val startTime: java.time.LocalTime?,
    @field:NotNull val endTime: java.time.LocalTime?,
    @field:NotNull val effectiveFrom: java.time.LocalDate?,
    val effectiveUntil: java.time.LocalDate? = null,
)

data class EndRuleRequest(val effectiveUntil: java.time.LocalDate? = null)

data class AvailabilityExceptionRequest(
    @field:NotNull val resourceRef: UUID?,
    @field:NotNull val type: String?,
    @field:NotNull val startsAt: Instant?,
    @field:NotNull val endsAt: Instant?,
    val reason: String? = null,
)

data class AvailabilityChangeResponse(
    val ref: UUID,
    /** R20: bookings this edit left outside published availability. */
    val conflicts: List<ConflictResponse>,
)

data class ConflictResponse(
    val bookingRef: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val state: String,
    val resourceRef: UUID,
)

data class ProviderRequest(
    @field:NotNull val name: String?,
    @field:NotNull val timezone: String?,
    @field:NotNull val confirmationMode: String?,
    val minLeadMinutes: Int? = null,
    val bookingHorizonDays: Int? = null,
    val cancellationWindowMinutes: Int? = null,
    val approvalHoldTtlMinutes: Int? = null,
    val autoCompleteGraceDays: Int? = null,
)

data class ResourceRequest(
    @field:NotNull val name: String?,
    /** Optional IdP subject, granting that person read access to this resource (R24). */
    val linkedSubject: String? = null,
)

data class ServiceRequest(
    @field:NotNull val name: String?,
    @field:NotNull val durationMinutes: Int?,
    val capacity: Int? = null,
    val bufferBeforeMinutes: Int? = null,
    val bufferAfterMinutes: Int? = null,
    val slotStepMinutes: Int? = null,
)

data class CreatedRefResponse(val ref: UUID)
