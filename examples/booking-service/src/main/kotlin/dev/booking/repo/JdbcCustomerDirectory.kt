package dev.booking.repo

import dev.booking.core.booking.CustomerContact
import dev.booking.core.booking.CustomerDirectory
import dev.booking.sys.IdGenerator
import dev.booking.sys.SqlSource
import java.time.Clock
import java.time.ZoneOffset
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Maps verified IdP subjects onto local customer profiles (LD3).
 *
 * The upsert is one statement so that two concurrent first requests from the same
 * subject cannot create two profiles — the unique constraint on `idp_subject`
 * arbitrates, not application code.
 */
@Repository
class JdbcCustomerDirectory(
    private val jdbc: JdbcClient,
    private val ids: IdGenerator,
    private val clock: Clock,
    sql: SqlSource,
) : CustomerDirectory {

    private val upsertCustomerSql = sql.load("customer/upsert_customer")
    private val findIdSql = sql.load("customer/find_id_by_subject")
    private val findIdByRefSql = sql.load("customer/find_id_by_ref")

    override fun findIdByRef(customerRef: java.util.UUID): Long? =
        jdbc.sql(findIdByRefSql)
            .param("customerRef", customerRef)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun findIdBySubject(idpSubject: String): Long? =
        jdbc.sql(findIdSql)
            .param("idpSubject", idpSubject)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun findOrCreate(idpSubject: String, contact: CustomerContact): Long =
        jdbc.sql(upsertCustomerSql)
            .param("publicRef", ids.newId())
            .param("idpSubject", idpSubject)
            .param("displayName", contact.displayName)
            .param("email", contact.email)
            .param("phone", contact.phone)
            .param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .query(Long::class.java)
            .single()
}
