package dev.booking.api

import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingRule
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

/**
 * The single owner of "what does this outcome mean on the wire".
 *
 * Every booking endpoint routes its outcome through here, so the mapping from a
 * violated rule to a status code exists once, and every outcome — success and
 * rejection alike — is logged exactly once.  Business code does not log; this
 * boundary does.
 */
@Component
class BookingOutcomeResponder {

    private val log = LoggerFactory.getLogger(javaClass)

    fun respond(outcome: BookingOutcome): ResponseEntity<Any> =
        when (outcome) {
            is BookingOutcome.Created -> {
                log.info(
                    "booking {} {} bookingRef={} replay={}",
                    if (outcome.booking.wasReplay) "replayed" else "created",
                    outcome.booking.state,
                    outcome.booking.ref,
                    outcome.booking.wasReplay,
                )
                ResponseEntity
                    .status(if (outcome.booking.wasReplay) HttpStatus.OK else HttpStatus.CREATED)
                    .body(BookingResponse(outcome.booking.ref, outcome.booking.state.name))
            }

            is BookingOutcome.Transitioned -> {
                log.info(
                    "booking transitioned bookingRef={} state={} sequenceNo={}",
                    outcome.booking.ref,
                    outcome.booking.state,
                    outcome.booking.sequenceNo,
                )
                ResponseEntity.ok(
                    TransitionResponse(
                        outcome.booking.ref,
                        outcome.booking.state.name,
                        outcome.booking.sequenceNo,
                    ),
                )
            }

            // R18 requires an unknown booking and another tenant's booking be
            // indistinguishable, so both land here.
            BookingOutcome.NotFound -> {
                log.info("booking request rejected reason=not-found-or-not-yours")
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(
                        ErrorResponse(
                            code = "NOT_FOUND",
                            rule = "R18",
                            message = "no such booking",
                        ),
                    )
            }

            is BookingOutcome.Rejected -> {
                val status = statusFor(outcome.rule)
                log.info(
                    "booking rejected rule={} requirement={} status={}",
                    outcome.rule.name,
                    outcome.rule.requirement,
                    status.value(),
                )
                ResponseEntity
                    .status(status)
                    .body(
                        ErrorResponse(
                            code = outcome.rule.sqlState,
                            rule = outcome.rule.requirement,
                            message = outcome.detail,
                        ),
                    )
            }
        }

    /** An attendance value outside the closed set is a malformed request. */
    fun badAttendance(supplied: String?): ResponseEntity<Any> {
        log.info("attendance rejected reason=unknown-value supplied={}", supplied)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(
                ErrorResponse(
                    code = "BAD_REQUEST",
                    rule = "UC9",
                    message = "attendance must be COMPLETED or NO_SHOW",
                ),
            )
    }

    /**
     * Exhaustive by construction — adding a rule without deciding its status is a
     * compile error, which is the point.  Mapping follows
     * architecture-design.md section 5.
     */
    private fun statusFor(rule: BookingRule): HttpStatus =
        when (rule) {
            BookingRule.SESSION_OVERLAP,
            BookingRule.SESSION_FULL,
            BookingRule.FORBIDDEN_TRANSITION,
            -> HttpStatus.CONFLICT

            BookingRule.OUTSIDE_AVAILABILITY,
            BookingRule.INSIDE_LEAD_TIME,
            BookingRule.BEYOND_HORIZON,
            BookingRule.PAST_CANCELLATION_WINDOW,
            BookingRule.ATTENDANCE_TOO_EARLY,
            -> HttpStatus.UNPROCESSABLE_ENTITY

            BookingRule.NOT_BOOKABLE -> HttpStatus.NOT_FOUND
        }
}
