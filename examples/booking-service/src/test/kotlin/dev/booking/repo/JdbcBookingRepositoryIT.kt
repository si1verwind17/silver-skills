package dev.booking.repo

import dev.booking.core.booking.ActorType
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingRule
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.ConfirmationMode
import dev.booking.core.booking.CreateBookingCommand
import dev.booking.core.booking.CustomerContact
import dev.booking.core.booking.HoldReason
import dev.booking.sys.IdGenerator
import dev.booking.sys.JdbcConfig
import dev.booking.sys.SqlSource
import dev.booking.sys.UuidV7Generator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.random.RandomGenerator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Tier 3 — the data layer against a real PostgreSQL.
 *
 * These tests exist to catch what stubs structurally cannot: wrong SQL, a
 * parameter bound to the wrong type, a column renamed out from under the mapper,
 * and above all whether a `BK*` SQLSTATE really does survive Spring's exception
 * translation as a typed rule (stack-selection.md section 5.1).
 */
class JdbcBookingRepositoryIT {

    private val now = Instant.parse("2026-09-01T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ids: IdGenerator = UuidV7Generator(clock, RandomGenerator.getDefault())

    // The real wiring, not a hand-rolled template: this is the object the
    // application uses, so the translator under test is the one that ships.
    private val jdbc = JdbcClient.create(JdbcConfig().jdbcTemplate(TestDatabase.dataSource))
    private val repository = JdbcBookingRepository(jdbc, SqlSource())
    private val customers = JdbcCustomerDirectory(jdbc, ids, clock, SqlSource())

    private val serviceRef = UUID.fromString("00000000-0000-7000-8000-00000000a001")
    private val resourceRef = UUID.fromString("00000000-0000-7000-8000-00000000a002")
    private val slot = Instant.parse("2026-09-02T09:00:00Z")

    @BeforeTest
    fun resetFixtures() {
        jdbc.sql("DELETE FROM outbox_event").update()
        jdbc.sql("DELETE FROM booking_transition").update()
        jdbc.sql("DELETE FROM booking").update()
        jdbc.sql("DELETE FROM session").update()
        jdbc.sql("DELETE FROM availability_rule").update()
        jdbc.sql("DELETE FROM service_resource").update()
        jdbc.sql("DELETE FROM service").update()
        jdbc.sql("DELETE FROM resource").update()
        jdbc.sql("DELETE FROM customer").update()
        jdbc.sql("DELETE FROM provider").update()

        jdbc.sql(
            """
            INSERT INTO provider (public_ref, name, timezone, confirmation_mode_id)
            VALUES (gen_random_uuid(), 'Test Provider', 'UTC',
                    (SELECT confirmation_mode_id FROM confirmation_mode WHERE code = 'APPROVAL'))
            """.trimIndent(),
        ).update()
        jdbc.sql(
            "INSERT INTO resource (public_ref, provider_id, name) " +
                "SELECT :ref::uuid, provider_id, 'Room 1' FROM provider",
        ).param("ref", resourceRef).update()
        jdbc.sql(
            """
            INSERT INTO service (public_ref, provider_id, name, duration_minutes,
                                 capacity, buffer_after_minutes, slot_step_minutes)
            SELECT :ref::uuid, provider_id, 'Haircut', 30, 1, 15, 15 FROM provider
            """.trimIndent(),
        ).param("ref", serviceRef).update()
        jdbc.sql(
            "INSERT INTO service_resource (service_id, resource_id, provider_id) " +
                "SELECT s.service_id, r.resource_id, s.provider_id FROM service s, resource r",
        ).update()
        jdbc.sql(
            """
            INSERT INTO availability_rule (public_ref, resource_id, day_of_week,
                                           start_time, end_time, effective_from)
            SELECT gen_random_uuid(), resource_id,
                   EXTRACT(DOW FROM DATE '2026-09-02'), '08:00', '18:00', DATE '2026-01-01'
              FROM resource
            """.trimIndent(),
        ).update()
    }

    private fun serviceContext() = assertNotNull(
        repository.findServiceContext(serviceRef),
        "fixtures should resolve",
    )

    private fun resourceId() = assertNotNull(
        repository.findResourceId(resourceRef),
        "fixtures should resolve",
    )

    private fun command(
        startsAt: Instant = slot,
        idempotencyKey: String? = null,
        customerId: Long = customerId(),
    ) = CreateBookingCommand(
        customerId = customerId,
        serviceId = serviceContext().serviceId,
        resourceId = resourceId(),
        startsAt = startsAt,
        gate = HoldReason.AWAITING_PROVIDER_APPROVAL,
        actorType = ActorType.CUSTOMER,
        actorSubject = "sub-test",
        idempotencyKey = idempotencyKey,
        now = now,
        bookingRef = ids.newId(),
        eventId = ids.newId(),
    )

    private fun customerId() =
        customers.findOrCreate("sub-test", CustomerContact("Test", "t@x.test", null))

    private fun countOutbox() =
        jdbc.sql("SELECT count(*) FROM outbox_event").query(Int::class.java).single()

    @Test
    fun `resolves public references into internal identifiers and provider policy`() {
        val context = serviceContext()
        assertTrue(context.serviceId > 0)
        assertTrue(resourceId() > 0)
        assertEquals(ConfirmationMode.APPROVAL, context.confirmationMode)
    }

    @Test
    fun `an unknown reference resolves to nothing`() {
        assertNull(repository.findServiceContext(UUID.randomUUID()))
        assertNull(repository.findResourceId(UUID.randomUUID()))
    }

    @Test
    fun `creating a booking at an approval-mode provider holds it and emits one event`() {
        val outcome = repository.create(command())

        val created = (outcome as BookingOutcome.Created).booking
        assertEquals(BookingState.HELD, created.state)
        assertEquals(false, created.wasReplay)
        assertEquals(1, countOutbox(), "R22: exactly one event per transition")
        assertEquals(
            "BookingHeld",
            jdbc.sql(
                "SELECT et.code FROM outbox_event oe JOIN event_type et USING (event_type_id)",
            ).query(String::class.java).single(),
        )
    }

    @Test
    fun `replaying an idempotency key returns the original and emits no second event`() {
        val customer = customerId()
        val first = repository.create(command(idempotencyKey = "key-1", customerId = customer))
        val replay = repository.create(command(idempotencyKey = "key-1", customerId = customer))

        val original = (first as BookingOutcome.Created).booking
        val repeated = (replay as BookingOutcome.Created).booking
        assertEquals(original.ref, repeated.ref, "R15: a replay returns the original booking")
        assertTrue(repeated.wasReplay)
        assertEquals(1, countOutbox(), "R15: a replay must not emit a second event")
    }

    @Test
    fun `an overlapping booking is rejected as a typed rule, not an opaque failure`() {
        repository.create(command())

        // 09:20 falls inside the first booking's 30 minutes plus its 15-minute
        // buffer, so the exclusion constraint refuses it with BK001.
        val outcome = repository.create(command(startsAt = Instant.parse("2026-09-02T09:20:00Z")))

        val rejected = outcome as BookingOutcome.Rejected
        assertEquals(
            BookingRule.SESSION_OVERLAP, rejected.rule,
            "the custom SQLSTATE must survive Spring's exception translation",
        )
        assertEquals(1, countOutbox(), "a refused booking emits nothing")
    }

    @Test
    fun `a booking outside published availability is rejected as such`() {
        val outcome = repository.create(command(startsAt = Instant.parse("2026-09-02T19:00:00Z")))

        assertEquals(
            BookingRule.OUTSIDE_AVAILABILITY,
            (outcome as BookingOutcome.Rejected).rule,
        )
    }

    @Test
    fun `the customer directory is idempotent for one subject`() {
        val first = customers.findOrCreate("sub-repeat", CustomerContact("A", "a@x.test", null))
        val second = customers.findOrCreate("sub-repeat", CustomerContact("A", "a@x.test", null))
        assertEquals(first, second, "one subject must map to one profile")
    }
}
