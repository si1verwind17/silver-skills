package dev.booking.api

import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingRule
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.CreatedBooking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tier 2 — the boundary mapping.  Error paths are the least-exercised code in a
 * service, so every rule gets an assertion rather than a representative sample.
 */
class BookingOutcomeResponderTest {

    private val responder = BookingOutcomeResponder()

    @Test
    fun `a new booking is 201 and a replay is 200`() {
        val fresh = responder.respond(
            BookingOutcome.Created(CreatedBooking(UUID.randomUUID(), BookingState.HELD, false)),
        )
        assertEquals(201, fresh.statusCode.value())

        val replay = responder.respond(
            BookingOutcome.Created(CreatedBooking(UUID.randomUUID(), BookingState.HELD, true)),
        )
        assertEquals(200, replay.statusCode.value(), "R15 replay is not a new creation")
    }

    @Test
    fun `every rule maps to the status architecture section 5 specifies`() {
        val expected = mapOf(
            BookingRule.SESSION_OVERLAP to 409,
            BookingRule.SESSION_FULL to 409,
            BookingRule.FORBIDDEN_TRANSITION to 409,
            BookingRule.OUTSIDE_AVAILABILITY to 422,
            BookingRule.INSIDE_LEAD_TIME to 422,
            BookingRule.BEYOND_HORIZON to 422,
            BookingRule.PAST_CANCELLATION_WINDOW to 422,
            BookingRule.ATTENDANCE_TOO_EARLY to 422,
            BookingRule.NOT_BOOKABLE to 404,
        )
        assertEquals(
            BookingRule.entries.toSet(), expected.keys,
            "a rule without an expected status means this test drifted from the enum",
        )
        expected.forEach { (rule, status) ->
            val response = responder.respond(BookingOutcome.Rejected(rule, "detail"))
            assertEquals(status, response.statusCode.value(), "wrong status for $rule")
        }
    }

    @Test
    fun `a rejection body names the violated requirement`() {
        val response = responder.respond(
            BookingOutcome.Rejected(BookingRule.SESSION_OVERLAP, "occupied"),
        )
        val body = response.body as ErrorResponse
        assertEquals("BK001", body.code)
        assertTrue(body.rule.contains("R1"), "NF8 requires the response to name the rule")
    }
}
