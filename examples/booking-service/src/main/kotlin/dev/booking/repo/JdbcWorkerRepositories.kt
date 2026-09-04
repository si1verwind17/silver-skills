package dev.booking.repo

import dev.booking.core.gate.GateHandling
import dev.booking.core.gate.GateRejections
import dev.booking.core.gate.InboxRepository
import dev.booking.core.outbox.OutboxRepository
import dev.booking.core.outbox.PendingEvent
import dev.booking.core.sweep.SweepRepository
import dev.booking.sys.SqlSource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** The relay's view of the outbox (UC13). */
@Repository
class JdbcOutboxRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : OutboxRepository {

    private val claimSql = sql.load("outbox/claim_due_events")
    private val markDispatchedSql = sql.load("outbox/mark_dispatched")
    private val recordFailureSql = sql.load("outbox/record_failure")

    override fun claimDue(now: Instant, limit: Int): List<PendingEvent> =
        jdbc.sql(claimSql)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("limit", limit)
            .query { rs, _ ->
                PendingEvent(
                    outboxEventId = rs.getLong("outbox_event_id"),
                    eventId = rs.getObject("event_id", UUID::class.java),
                    bookingRef = rs.getObject("booking_ref", UUID::class.java),
                    eventType = rs.getString("event_type"),
                    schemaVersion = rs.getInt("schema_version"),
                    payload = rs.getString("payload"),
                    occurredAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    attemptCount = rs.getInt("attempt_count"),
                )
            }
            .list()

    override fun markDispatched(outboxEventId: Long, now: Instant) {
        jdbc.sql(markDispatchedSql)
            .param("outboxEventId", outboxEventId)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun recordFailure(
        outboxEventId: Long,
        error: String,
        nextAttemptAt: Instant,
        now: Instant,
    ) {
        jdbc.sql(recordFailureSql)
            .param("outboxEventId", outboxEventId)
            .param("lastError", error.take(2000))
            .param("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC))
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update()
    }
}

/** The two scheduled sweeps (UC11, UC12). */
@Repository
class JdbcSweepRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : SweepRepository {

    private val expireSql = sql.load("sweep/expire_holds")
    private val autoCompleteSql = sql.load("sweep/auto_complete")

    override fun expireHolds(now: Instant, limit: Int): Int = run(expireSql, now, limit)

    override fun autoComplete(now: Instant, limit: Int): Int = run(autoCompleteSql, now, limit)

    private fun run(statement: String, now: Instant, limit: Int): Int =
        jdbc.sql(statement)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("limit", limit)
            .query(Int::class.java)
            .single()
}

/** Consumer deduplication and the R28 rejection publish. */
@Repository
class JdbcInboxRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : InboxRepository, GateRejections {

    private val claimSql = sql.load("inbox/claim_message")
    private val markProcessedSql = sql.load("inbox/mark_processed")
    private val rejectionSql = sql.load("booking/record_gate_rejection")

    override fun claim(messageId: String, messageType: String, payload: String): Long? =
        jdbc.sql(claimSql)
            .param("messageId", messageId)
            .param("messageType", messageType)
            .param("payload", payload)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun markProcessed(inboxMessageId: Long, outcome: GateHandling, now: Instant) {
        jdbc.sql(markProcessedSql)
            .param("inboxMessageId", inboxMessageId)
            .param("outcome", outcome.name)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun recordRejection(bookingId: Long, eventId: UUID) {
        jdbc.sql(rejectionSql)
            .param("bookingId", bookingId)
            .param("eventId", eventId)
            .query(String::class.java)
            .optional()
    }
}
