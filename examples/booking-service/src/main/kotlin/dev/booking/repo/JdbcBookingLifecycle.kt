package dev.booking.repo

import dev.booking.core.booking.BookingIdentity
import dev.booking.core.booking.BookingLookup
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.BookingTransitions
import dev.booking.core.booking.ConfirmationMode
import dev.booking.core.booking.RescheduleCommand
import dev.booking.core.booking.TransitionCommand
import dev.booking.core.booking.TransitionedBooking
import dev.booking.sys.SqlSource
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Reads a booking's ownership and performs state changes.
 *
 * Both writes call a database function that owns the permitted-move table and the
 * outbox insert, so this class cannot accidentally move a booking illegally or
 * forget to publish the event.
 */
@Repository
class JdbcBookingLifecycle(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : BookingLookup, BookingTransitions {

    private val findIdentitySql = sql.load("booking/find_booking_identity")
    private val transitionSql = sql.load("booking/transition_booking")
    private val rescheduleSql = sql.load("booking/reschedule_booking")

    override fun findIdentity(ref: UUID): BookingIdentity? =
        jdbc.sql(findIdentitySql)
            .param("bookingRef", ref)
            .query { rs, _ ->
                BookingIdentity(
                    bookingId = rs.getLong("booking_id"),
                    ref = rs.getObject("public_ref", UUID::class.java),
                    customerId = rs.getLong("customer_id"),
                    providerId = rs.getLong("provider_id"),
                    confirmationMode = ConfirmationMode.valueOf(rs.getString("confirmation_mode")),
                )
            }
            .optional()
            .orElse(null)

    override fun transition(command: TransitionCommand): BookingOutcome =
        translatingRuleViolations {
            val sequenceNo = jdbc.sql(transitionSql)
                .param("bookingId", command.bookingId)
                .param("targetState", command.targetState.name)
                .param("actorType", command.actorType.name)
                .param("actorSubject", command.actorSubject)
                .param("reason", command.reason)
                .param("now", command.now.atOffset(ZoneOffset.UTC))
                .param("eventId", command.eventId)
                .param("gate", command.gate?.name)
                .param("completionSource", command.completionSource?.name)
                .query(Int::class.java)
                .single()
            BookingOutcome.Transitioned(
                TransitionedBooking(command.bookingRef, command.targetState, sequenceNo),
            )
        }

    override fun reschedule(command: RescheduleCommand): BookingOutcome =
        translatingRuleViolations {
            val sequenceNo = jdbc.sql(rescheduleSql)
                .param("bookingId", command.bookingId)
                .param("newResourceId", command.newResourceId)
                .param("newStartsAt", command.newStartsAt.atOffset(ZoneOffset.UTC))
                .param("actorType", command.actorType.name)
                .param("actorSubject", command.actorSubject)
                .param("now", command.now.atOffset(ZoneOffset.UTC))
                .param("eventId", command.eventId)
                .param("gate", command.gate?.name)
                .query(Int::class.java)
                .single()
            // The resulting state follows the gate the caller chose under R11.
            val state = if (command.gate == null) BookingState.CONFIRMED else BookingState.HELD
            BookingOutcome.Transitioned(
                TransitionedBooking(command.bookingRef, state, sequenceNo),
            )
        }
}
