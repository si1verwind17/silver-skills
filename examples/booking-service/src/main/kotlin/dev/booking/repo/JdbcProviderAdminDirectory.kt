package dev.booking.repo

import dev.booking.core.booking.ProviderAdminDirectory
import dev.booking.sys.SqlSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** R18 membership, resolved locally rather than trusted from a token claim (PD1). */
@Repository
class JdbcProviderAdminDirectory(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : ProviderAdminDirectory {

    private val administeredSql = sql.load("provider/administered_provider_ids")

    override fun administeredProviderIds(idpSubject: String): Set<Long> =
        jdbc.sql(administeredSql)
            .param("idpSubject", idpSubject)
            .query(Long::class.java)
            .list()
            // provider_id is NOT NULL, so filterNotNull only satisfies the JDBC
            // signature — it discards nothing in practice.
            .filterNotNull()
            .toSet()
}
