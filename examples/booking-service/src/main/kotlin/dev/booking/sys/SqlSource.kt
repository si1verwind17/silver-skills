package dev.booking.sys

import java.nio.charset.StandardCharsets
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Loads SQL from `.sql` resources rather than embedding it in string literals,
 * so statements are reviewable and diffable as SQL.
 *
 * Callers load in their constructor, which turns a missing or misnamed statement
 * into a startup failure rather than a per-request one — a packaging defect is
 * configuration, and configuration fails at boot.
 */
@Component
class SqlSource {

    fun load(name: String): String {
        val resource = ClassPathResource("sql/$name.sql")
        require(resource.exists()) { "SQL resource 'sql/$name.sql' is missing from the build" }
        return resource.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
