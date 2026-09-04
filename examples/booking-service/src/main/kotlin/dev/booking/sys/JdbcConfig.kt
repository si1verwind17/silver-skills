package dev.booking.sys

import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Replaces the auto-configured `JdbcTemplate` with one that understands this
 * service's `BK*` SQLSTATEs.  Spring Boot builds its `JdbcClient` from whatever
 * `JdbcTemplate` bean exists, so installing the translator here covers every
 * query in the service rather than each call site remembering to.
 */
@Configuration
class JdbcConfig {

    @Bean
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate =
        JdbcTemplate(dataSource).apply {
            exceptionTranslator = BookingSqlExceptionTranslator()
        }
}
