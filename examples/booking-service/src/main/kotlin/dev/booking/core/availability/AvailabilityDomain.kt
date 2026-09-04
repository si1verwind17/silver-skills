package dev.booking.core.availability

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One bookable start time, as UC4 defines it. */
data class AvailableSlot(
    val resourceRef: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val remainingCapacity: Int,
)

sealed interface AvailabilityOutcome {
    data class Found(val slots: List<AvailableSlot>) : AvailabilityOutcome
    data class WindowRejected(val detail: String) : AvailabilityOutcome
}

/**
 * The rule governing how wide a single availability query may be (PD7).
 *
 * A pure object rather than a check inside the service, so the boundary case is
 * testable without a database.  UC4 requires an over-wide window be rejected
 * outright rather than silently truncated — a truncated answer looks like a
 * complete one, which is the failure worth preventing.
 */
object AvailabilityWindow {

    val maximumSpan: Duration = Duration.ofDays(62)

    fun validate(from: Instant, to: Instant): String? = when {
        !to.isAfter(from) -> "the window must end after it starts"
        Duration.between(from, to) > maximumSpan ->
            "a single search may span at most ${maximumSpan.toDays()} days"
        else -> null
    }
}
