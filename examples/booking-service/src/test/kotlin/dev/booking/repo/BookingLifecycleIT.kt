package dev.booking.repo

import dev.booking.core.booking.ActorType
import dev.booking.core.booking.BookingLifecycleService
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.CreateBookingCommand
import dev.booking.core.booking.CustomerContact
import dev.booking.core.booking.HoldReason
import dev.booking.core.gate.GateHandling
import dev.booking.core.gate.GateOutcome
import dev.booking.core.gate.GateResolution
import dev.booking.core.gate.GateResolutionService
import dev.booking.core.outbox.EventPublisher
import dev.booking.core.outbox.OutboxRelay
import dev.booking.core.outbox.PendingEvent
import dev.booking.core.sweep.SweepService
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
 * Tier 3 — the lifecycle, the relay and the gate saga against a real PostgreSQL.
 *
 * These are the seam tests: each exercises a path that spans the application and
 * the database, where a stub on either side would prove nothing. The last one is
 * the saga's compensation branch (R28), which is the single behaviour this
 * design's correctness most depends on and cannot be shown with fakes.
 */
class BookingLifecycleIT {

    private val bookedAt = Instant.parse("2026-09-01T08:00:00Z")
    private val slot = Instant.parse("2026-09-02T09:00:00Z")
    private val clock = Clock.fixed(bookedAt, ZoneOffset.UTC)
    private val ids: IdGenerator = UuidV7Generator(clock, RandomGenerator.getDefault())

    private val jdbc = JdbcClient.create(JdbcConfig().jdbcTemplate(TestDatabase.dataSource))
    private val sql = SqlSource()
    private val bookings = JdbcBookingRepository(jdbc, sql)
    private val lifecycleRepo = JdbcBookingLifecycle(jdbc, sql)
    private val customers = JdbcCustomerDirectory(jdbc, ids, clock, sql)
    private val admins = JdbcProviderAdminDirectory(jdbc, sql)
    private val outbox = JdbcOutboxRepository(jdbc, sql)
    private val sweepRepo = JdbcSweepRepository(jdbc, sql)
    private val inbox = JdbcInboxRepository(jdbc, sql)

    private val lifecycle = BookingLifecycleService(
        lifecycleRepo, lifecycleRepo, customers, admins, clock, ids,
    )

    private val serviceRef = UUID.fromString("00000000-0000-7000-8000-00000000b001")
    private val resourceRef = UUID.fromString("00000000-0000-7000-8000-00000000b002")

    @BeforeTest
    fun fixtures() {
        listOf(
            "outbox_event", "inbox_message", "booking_transition", "booking", "session",
            "availability_rule", "service_resource", "service", "resource",
            "provider_admin", "customer", "provider",
        ).forEach { jdbc.sql("DELETE FROM $it").update() }

        jdbc.sql(
            """
            INSERT INTO provider (public_ref, name, timezone, confirmation_mode_id)
            VALUES (gen_random_uuid(), 'Lifecycle Provider', 'UTC',
                    (SELECT confirmation_mode_id FROM confirmation_mode WHERE code = 'APPROVAL'))
            """.trimIndent(),
        ).update()
        jdbc.sql(
            "INSERT INTO provider_admin (provider_id, idp_subject) SELECT provider_id, 'sub-owner' FROM provider",
        ).update()
        jdbc.sql(
            "INSERT INTO resource (public_ref, provider_id, name) SELECT :ref::uuid, provider_id, 'Room' FROM provider",
        ).param("ref", resourceRef).update()
        jdbc.sql(
            """
            INSERT INTO service (public_ref, provider_id, name, duration_minutes, capacity, slot_step_minutes)
            SELECT :ref::uuid, provider_id, 'Consult', 30, 1, 15 FROM provider
            """.trimIndent(),
        ).param("ref", serviceRef).update()
        jdbc.sql(
            "INSERT INTO service_resource (service_id, resource_id, provider_id) " +
                "SELECT s.service_id, r.resource_id, s.provider_id FROM service s, resource r",
        ).update()
        jdbc.sql(
            """
            INSERT INTO availability_rule (public_ref, resource_id, day_of_week, start_time, end_time, effective_from)
            SELECT gen_random_uuid(), resource_id, EXTRACT(DOW FROM DATE '2026-09-02'),
                   '08:00', '18:00', DATE '2026-01-01' FROM resource
            """.trimIndent(),
        ).update()
    }

    private fun heldBooking(subject: String = "sub-customer"): UUID {
        val customerId = customers.findOrCreate(subject, CustomerContact(null, null, null))
        val outcome = bookings.create(
            CreateBookingCommand(
                customerId = customerId,
                serviceId = assertNotNull(bookings.findServiceContext(serviceRef)).serviceId,
                resourceId = assertNotNull(bookings.findResourceId(resourceRef)),
                startsAt = slot,
                gate = HoldReason.AWAITING_PROVIDER_APPROVAL,
                actorType = ActorType.CUSTOMER,
                actorSubject = subject,
                idempotencyKey = null,
                now = bookedAt,
                bookingRef = ids.newId(),
                eventId = ids.newId(),
            ),
        )
        return (outcome as BookingOutcome.Created).booking.ref
    }

    private fun events(): List<Pair<String, Int?>> =
        jdbc.sql(
            "SELECT et.code, oe.transition_sequence_no FROM outbox_event oe " +
                "JOIN event_type et USING (event_type_id) ORDER BY oe.outbox_event_id",
        ).query { rs, _ ->
            rs.getString(1) to rs.getObject(2)?.let { (it as Number).toInt() }
        }.list()

    @Test
    fun `a provider administrator can approve a held booking`() {
        val ref = heldBooking()

        val outcome = lifecycle.approve(ref, "sub-owner")

        val transitioned = (outcome as BookingOutcome.Transitioned).booking
        assertEquals(BookingState.CONFIRMED, transitioned.state)
        assertEquals(2, transitioned.sequenceNo)
        assertEquals(listOf("BookingHeld" to 1, "BookingConfirmed" to 2), events())
    }

    @Test
    fun `a stranger cannot approve, and cannot tell the booking exists`() {
        val ref = heldBooking()

        assertTrue(lifecycle.approve(ref, "sub-stranger") is BookingOutcome.NotFound)
        assertTrue(
            lifecycle.approve(UUID.randomUUID(), "sub-owner") is BookingOutcome.NotFound,
            "R18: a real booking and an imaginary one must answer identically",
        )
        assertEquals(1, events().size, "a refused action emits nothing")
    }

    @Test
    fun `the owning customer cannot approve their own booking`() {
        val ref = heldBooking()
        assertTrue(
            lifecycle.approve(ref, "sub-customer") is BookingOutcome.NotFound,
            "approval is provider-only, and the refusal must not reveal why",
        )
    }

    @Test
    fun `the relay publishes committed events and marks them dispatched`() {
        heldBooking()
        val published = mutableListOf<PendingEvent>()
        val relay = OutboxRelay(
            outbox,
            object : EventPublisher {
                override fun publish(event: PendingEvent) { published += event }
            },
            clock,
            batchSize = 10,
        )

        val report = relay.dispatchDue()

        assertEquals(1, report.published)
        assertEquals("BookingHeld", published.single().eventType)
        assertTrue(
            published.single().payload.contains("\"state\""),
            "the payload is built by the database, not assembled by the relay",
        )
        assertEquals(
            0,
            jdbc.sql("SELECT count(*) FROM outbox_event WHERE dispatched_at IS NULL")
                .query(Int::class.java).single(),
        )
    }

    @Test
    fun `a broker outage leaves the event queued for a later attempt`() {
        heldBooking()
        val relay = OutboxRelay(
            outbox,
            object : EventPublisher {
                override fun publish(event: PendingEvent) = throw IllegalStateException("broker down")
            },
            clock,
            batchSize = 10,
        )

        assertEquals(1, relay.dispatchDue().failed)

        val row = jdbc.sql(
            "SELECT attempt_count, dispatched_at IS NULL AS pending, last_error FROM outbox_event",
        ).query { rs, _ -> Triple(rs.getInt(1), rs.getBoolean(2), rs.getString(3)) }.single()
        assertEquals(1, row.first)
        assertTrue(row.second, "R22: an unacknowledged event is never marked delivered")
        assertEquals("broker down", row.third)
    }

    @Test
    fun `a gate resolution arriving after the hold expired cannot resurrect the booking`() {
        val ref = heldBooking()

        // The hold deadline is capped at the booking's start time (R8), so sweeping
        // after that expires it and releases the capacity.
        val afterDeadline = Clock.fixed(slot.plusSeconds(60), ZoneOffset.UTC)
        val expired = SweepService(sweepRepo, afterDeadline, batchSize = 100).expireLapsedHolds()
        assertEquals(1, expired)

        val gates = GateResolutionService(
            inbox, lifecycleRepo, lifecycleRepo, inbox, afterDeadline, ids,
        )
        val handling = gates.handle(
            GateResolution(
                messageId = "payment-1",
                bookingRef = ref,
                holdReason = "AWAITING_PROVIDER_APPROVAL",
                outcome = GateOutcome.RESOLVED,
                reason = null,
                rawPayload = """{"messageId":"payment-1"}""",
            ),
        )

        assertEquals(GateHandling.REJECTED, handling)
        assertEquals(
            BookingState.EXPIRED.name,
            jdbc.sql(
                "SELECT bs.code FROM booking b JOIN booking_state bs USING (booking_state_id)",
            ).query(String::class.java).single(),
            "EXPIRED is terminal — a late resolution must not undo it",
        )

        val rejection = events().last()
        assertEquals("BookingGateResolutionRejected", rejection.first)
        assertNull(rejection.second, "a rejection reports no transition, because none happened")
    }

    @Test
    fun `a redelivered gate resolution is a no-op against the real inbox`() {
        val ref = heldBooking()
        val gates = GateResolutionService(inbox, lifecycleRepo, lifecycleRepo, inbox, clock, ids)
        val message = GateResolution(
            messageId = "payment-2",
            bookingRef = ref,
            holdReason = "AWAITING_PROVIDER_APPROVAL",
            outcome = GateOutcome.RESOLVED,
            reason = null,
            rawPayload = """{"messageId":"payment-2"}""",
        )

        assertEquals(GateHandling.APPLIED, gates.handle(message))
        assertEquals(GateHandling.DUPLICATE, gates.handle(message))
        assertEquals(
            listOf("BookingHeld" to 1, "BookingConfirmed" to 2), events(),
            "R29: the redelivery must add no event",
        )
    }
}
