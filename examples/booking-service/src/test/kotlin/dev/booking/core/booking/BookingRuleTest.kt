package dev.booking.core.booking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookingRuleTest {

    @Test
    fun `every rule maps back from its SQLSTATE`() {
        BookingRule.entries.forEach { rule ->
            assertEquals(rule, BookingRule.ofSqlState(rule.sqlState))
        }
    }

    @Test
    fun `SQLSTATEs are distinct`() {
        // Copy-paste in a case list is exactly the kind of defect that hides in
        // error-handling code, so it gets an explicit test.
        val codes = BookingRule.entries.map { it.sqlState }
        assertEquals(codes.size, codes.toSet().size, "duplicate SQLSTATE among booking rules")
    }

    @Test
    fun `an unknown SQLSTATE is not a booking rule`() {
        assertNull(BookingRule.ofSqlState("23505"))
    }
}
