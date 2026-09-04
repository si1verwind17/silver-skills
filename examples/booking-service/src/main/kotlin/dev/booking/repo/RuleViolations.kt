package dev.booking.repo

import dev.booking.core.booking.BookingOutcome
import dev.booking.sys.BookingRuleViolation

/**
 * Turns a deliberate database rejection into a domain outcome.
 *
 * Every write path needs this and none of them should own a private copy — a
 * copy-pasted catch block is exactly the plumbing that drifts. Anything not
 * carrying a `BK*` SQLSTATE is a defect and is left to propagate.
 */
internal inline fun translatingRuleViolations(block: () -> BookingOutcome): BookingOutcome =
    try {
        block()
    } catch (violation: BookingRuleViolation) {
        BookingOutcome.Rejected(violation.rule, violation.message ?: violation.rule.name)
    }
