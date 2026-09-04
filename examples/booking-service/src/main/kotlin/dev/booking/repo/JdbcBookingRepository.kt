package dev.booking.repo

import dev.booking.core.booking.BookingCatalog
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingRepository
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.ConfirmationMode
import dev.booking.core.booking.CreateBookingCommand
import dev.booking.core.booking.CreatedBooking
import dev.booking.core.booking.ServiceContext
import dev.booking.sys.SqlSource
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Booking creation and the lookups it needs.
 *
 * Contains no business rules: it binds parameters, calls a database function and
 * reports the outcome.  SQL lives in `.sql` resources and is loaded at
 * construction, so a missing statement kills startup rather than a request.
 */
@Repository
class JdbcBookingRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : BookingRepository, BookingCatalog {

    private val createBookingSql = sql.load("booking/create_booking")
    private val findServiceContextSql = sql.load("booking/find_service_context")
    private val findResourceIdSql = sql.load("booking/find_resource_id")

    override fun findServiceContext(serviceRef: UUID): ServiceContext? =
        jdbc.sql(findServiceContextSql)
            .param("serviceRef", serviceRef)
            .query { rs, _ ->
                ServiceContext(
                    serviceId = rs.getLong("service_id"),
                    providerId = rs.getLong("provider_id"),
                    confirmationMode = ConfirmationMode.valueOf(rs.getString("confirmation_mode")),
                )
            }
            .optional()
            .orElse(null)

    override fun findResourceId(resourceRef: UUID): Long? =
        jdbc.sql(findResourceIdSql)
            .param("resourceRef", resourceRef)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun create(command: CreateBookingCommand): BookingOutcome =
        translatingRuleViolations {
            val created = jdbc.sql(createBookingSql)
                .param("customerId", command.customerId)
                .param("serviceId", command.serviceId)
                .param("resourceId", command.resourceId)
                .param("startsAt", command.startsAt.atOffset(ZoneOffset.UTC))
                .param("holdReason", command.gate?.name)
                .param("actorType", command.actorType.name)
                .param("actorSubject", command.actorSubject)
                .param("idempotencyKey", command.idempotencyKey)
                .param("now", command.now.atOffset(ZoneOffset.UTC))
                .param("bookingRef", command.bookingRef)
                .param("eventId", command.eventId)
                .query { rs, _ ->
                    CreatedBooking(
                        ref = rs.getObject("public_ref", UUID::class.java),
                        state = BookingState.ofCode(rs.getString("state_code")),
                        wasReplay = rs.getBoolean("was_replay"),
                    )
                }
                .single()
            BookingOutcome.Created(created)
        }
}
