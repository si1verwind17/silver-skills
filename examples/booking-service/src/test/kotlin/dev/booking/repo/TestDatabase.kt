package dev.booking.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A real PostgreSQL for the integration tier.  A stub can never fail because of
 * the database, so only this tier catches wrong SQL, schema drift and mapping
 * bugs — which is most of what this service's data layer is.
 *
 * Testcontainers is the default and the CI path.  When `BOOKING_TEST_JDBC_URL`
 * is set the tests run against that database instead, which is how they run
 * where Docker cannot publish ports.  The schema comes from the same Flyway
 * migrations the application uses, generated from the authoritative DDL.
 */
object TestDatabase {

    private const val IMAGE = "postgres:18-alpine"

    private val externalUrl: String? = System.getenv("BOOKING_TEST_JDBC_URL")

    // PostgreSQLContainer is no longer self-typed in Testcontainers 2.x, so it
    // takes no type argument.
    private val container: PostgreSQLContainer? by lazy {
        if (externalUrl != null) null
        else PostgreSQLContainer(IMAGE).apply { start() }
    }

    val dataSource: DataSource by lazy {
        val source = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = externalUrl ?: container!!.jdbcUrl
                username = System.getenv("BOOKING_TEST_DB_USER") ?: container!!.username
                password = System.getenv("BOOKING_TEST_DB_PASSWORD") ?: container!!.password
                maximumPoolSize = 4
            },
        )
        Flyway.configure()
            .dataSource(source)
            .cleanDisabled(false)
            .load()
            .apply { clean(); migrate() }
        source
    }
}
