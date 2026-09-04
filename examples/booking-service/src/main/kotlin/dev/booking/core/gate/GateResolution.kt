package dev.booking.core.gate

import java.time.Instant
import java.util.UUID

/** A decision made by another service about a booking this service is holding (UC14). */
data class GateResolution(
    val messageId: String,
    val bookingRef: UUID,
    val holdReason: String,
    val outcome: GateOutcome,
    val reason: String?,
    val rawPayload: String,
)

enum class GateOutcome { RESOLVED, REJECTED }

/** What handling a resolution did — reported so the consumer can log one outcome. */
enum class GateHandling {
    /** The hold was resolved and the booking moved. */
    APPLIED,

    /** Already seen; R29 requires a redelivery change nothing. */
    DUPLICATE,

    /** No such booking. */
    UNKNOWN_BOOKING,

    /** The gate could not be applied, so a rejection was published for the sender (R28). */
    REJECTED,
}

interface InboxRepository {
    /** Records the message, or returns null if it has already been seen (R29). */
    fun claim(messageId: String, messageType: String, payload: String): Long?

    fun markProcessed(inboxMessageId: Long, outcome: GateHandling, now: Instant)
}

interface GateRejections {
    /** Publishes BookingGateResolutionRejected without changing the booking (R28). */
    fun recordRejection(bookingId: Long, eventId: UUID)
}
