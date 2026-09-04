package dev.booking.sys

import dev.booking.core.booking.BookingRule
import java.sql.SQLException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.support.AbstractFallbackSQLExceptionTranslator
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/**
 * A rejection the database raised on purpose, carrying which rule was violated.
 * Thrown by the translator below and converted to a domain outcome inside the
 * repository, so it never escapes the data-access layer.
 */
class BookingRuleViolation(
    val rule: BookingRule,
    message: String,
    cause: SQLException,
) : DataAccessException(message, cause)

/**
 * Keeps custom SQLSTATEs from being swallowed.
 *
 * Spring maps `SQLException`s onto its own hierarchy, and the nine `BK*` codes
 * raised by ddl/03-functions.sql are not codes it knows — left alone they arrive
 * as `UncategorizedSQLException` with the rule buried in a message string, which
 * would turn every business rejection into an opaque 500 and break NF8's
 * requirement that an error names the rule it violated.  This was identified as
 * the highest-likelihood defect of this stack in stack-selection.md section 5.1.
 *
 * Anything that is not a `BK*` code falls through to Spring's own translation.
 */
class BookingSqlExceptionTranslator : AbstractFallbackSQLExceptionTranslator() {

    init {
        setFallbackTranslator(SQLExceptionSubclassTranslator())
    }

    override fun doTranslate(task: String, sql: String?, ex: SQLException): DataAccessException? {
        val rule = ex.sqlState?.let(BookingRule::ofSqlState) ?: return null
        return BookingRuleViolation(rule, ex.message ?: rule.name, ex)
    }
}
