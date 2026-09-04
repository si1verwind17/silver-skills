package dev.booking.repo

import dev.booking.core.management.AvailabilityAdminRepository
import dev.booking.core.management.AvailabilityConflict
import dev.booking.core.management.AvailabilityExceptionDraft
import dev.booking.core.management.AvailabilityRuleDraft
import dev.booking.sys.SqlSource
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Publishing availability (UC2, UC3).
 *
 * Each write is scoped to the provider in the SQL itself, so a resource belonging
 * to another provider produces no row rather than a cross-tenant write.
 */
@Repository
class JdbcAvailabilityAdminRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : AvailabilityAdminRepository {

    private val insertRuleSql = sql.load("management/insert_availability_rule")
    private val endRuleSql = sql.load("management/end_availability_rule")
    private val insertExceptionSql = sql.load("management/insert_availability_exception")
    private val conflictsSql = sql.load("management/availability_conflicts")

    override fun insertRule(providerId: Long, publicRef: UUID, draft: AvailabilityRuleDraft): Boolean =
        jdbc.sql(insertRuleSql)
            .param("publicRef", publicRef)
            .param("resourceRef", draft.resourceRef)
            .param("providerId", providerId)
            .param("dayOfWeek", draft.dayOfWeek)
            .param("startTime", draft.startTime)
            .param("endTime", draft.endTime)
            .param("effectiveFrom", draft.effectiveFrom)
            .param("effectiveUntil", draft.effectiveUntil)
            .query(Long::class.java)
            .optional()
            .isPresent

    override fun endRule(
        providerId: Long,
        ruleRef: UUID,
        effectiveUntil: LocalDate,
        now: Instant,
    ): Boolean =
        jdbc.sql(endRuleSql)
            .param("ruleRef", ruleRef)
            .param("providerId", providerId)
            .param("effectiveUntil", effectiveUntil)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update() == 1

    override fun insertException(
        providerId: Long,
        publicRef: UUID,
        draft: AvailabilityExceptionDraft,
    ): Boolean =
        jdbc.sql(insertExceptionSql)
            .param("publicRef", publicRef)
            .param("resourceRef", draft.resourceRef)
            .param("providerId", providerId)
            .param("type", draft.type.name)
            .param("startsAt", draft.startsAt.atOffset(ZoneOffset.UTC))
            .param("endsAt", draft.endsAt.atOffset(ZoneOffset.UTC))
            .param("reason", draft.reason)
            .query(Long::class.java)
            .optional()
            .isPresent

    override fun conflictsFor(providerId: Long, now: Instant): List<AvailabilityConflict> =
        jdbc.sql(conflictsSql)
            .param("providerId", providerId)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .query { rs, _ ->
                AvailabilityConflict(
                    bookingRef = rs.getObject("public_ref", UUID::class.java),
                    startsAt = rs.getObject("starts_at", OffsetDateTime::class.java).toInstant(),
                    endsAt = rs.getObject("ends_at", OffsetDateTime::class.java).toInstant(),
                    state = rs.getString("state"),
                    resourceRef = rs.getObject("resource_ref", UUID::class.java),
                )
            }
            .list()
}
