package dev.booking.repo

import dev.booking.core.booking.ActorType
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.CreateBookingCommand
import dev.booking.core.booking.CustomerContact
import dev.booking.core.management.AvailabilityExceptionDraft
import dev.booking.core.management.AvailabilityManagementService
import dev.booking.core.management.AvailabilityRuleDraft
import dev.booking.core.management.ExceptionType
import dev.booking.core.management.ManagementOutcome
import dev.booking.sys.IdGenerator
import dev.booking.sys.JdbcConfig
import dev.booking.sys.SqlSource
import dev.booking.sys.UuidV7Generator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.random.RandomGenerator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tier 3 — UC2, UC3 and above all R20.
 *
 * R20 is the requirement most easily broken by a well-meaning implementation:
 * withdrawing availability must never cancel a booking. That can only be shown
 * against a real database, because the conflict set is computed by the same
 * function that computes availability.
 */
class AvailabilityManagementIT {

    private val now = Instant.parse("2026-09-01T08:00:00Z")
    private val slot = Instant.parse("2026-09-02T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ids: IdGenerator = UuidV7Generator(clock, RandomGenerator.getDefault())

    private val jdbc = JdbcClientHolder.client
    private val sql = SqlSource()
    private val admin = JdbcAvailabilityAdminRepository(jdbc, sql)
    private val listings = JdbcListingRepository(jdbc, sql)
    private val admins = JdbcProviderAdminDirectory(jdbc, sql)
    private val bookings = JdbcBookingRepository(jdbc, sql)
    private val customers = JdbcCustomerDirectory(jdbc, ids, clock, sql)

    private val management = AvailabilityManagementService(admin, listings, admins, clock, ids)

    private lateinit var providerRef: UUID
    private val resourceRef = UUID.fromString("00000000-0000-7000-8000-00000000c002")
    private val serviceRef = UUID.fromString("00000000-0000-7000-8000-00000000c001")

    @BeforeTest
    fun fixtures() {
        listOf(
            "outbox_event", "inbox_message", "booking_transition", "booking", "session",
            "availability_exception", "availability_rule", "service_resource", "service",
            "resource", "provider_admin", "customer", "provider",
        ).forEach { jdbc.sql("DELETE FROM $it").update() }

        providerRef = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO provider (public_ref, name, timezone, confirmation_mode_id)
            VALUES (:ref::uuid, 'Managed Provider', 'UTC',
                    (SELECT confirmation_mode_id FROM confirmation_mode WHERE code = 'INSTANT'))
            """.trimIndent(),
        ).param("ref", providerRef).update()
        jdbc.sql("INSERT INTO provider_admin (provider_id, idp_subject) SELECT provider_id, 'sub-owner' FROM provider").update()
        jdbc.sql("INSERT INTO resource (public_ref, provider_id, name) SELECT :ref::uuid, provider_id, 'Room' FROM provider")
            .param("ref", resourceRef).update()
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
    }

    private fun publishWorkingDay(): ManagementOutcome =
        management.publishRule(
            providerRef,
            "sub-owner",
            AvailabilityRuleDraft(
                resourceRef = resourceRef,
                dayOfWeek = LocalDate.parse("2026-09-02").dayOfWeek.value % 7,
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(18, 0),
                effectiveFrom = LocalDate.parse("2026-01-01"),
                effectiveUntil = null,
            ),
        )

    private fun bookTheSlot(): UUID {
        val customerId = customers.findOrCreate("sub-customer", CustomerContact(null, null, null))
        val outcome = bookings.create(
            CreateBookingCommand(
                customerId = customerId,
                serviceId = assertNotNull(bookings.findServiceContext(serviceRef)).serviceId,
                resourceId = assertNotNull(bookings.findResourceId(resourceRef)),
                startsAt = slot,
                gate = null,
                actorType = ActorType.CUSTOMER,
                actorSubject = "sub-customer",
                idempotencyKey = null,
                now = now,
                bookingRef = ids.newId(),
                eventId = ids.newId(),
            ),
        )
        return (outcome as BookingOutcome.Created).booking.ref
    }

    @Test
    fun `publishing a rule makes the day bookable and reports no conflicts`() {
        val outcome = publishWorkingDay()

        val applied = outcome as ManagementOutcome.Applied
        assertTrue(applied.conflicts.isEmpty())
        assertTrue(bookTheSlot().toString().isNotEmpty())
    }

    @Test
    fun `a caller who does not administer the provider changes nothing`() {
        val outcome = management.publishRule(
            providerRef,
            "sub-stranger",
            AvailabilityRuleDraft(resourceRef, 3, LocalTime.of(8, 0), LocalTime.of(9, 0), LocalDate.parse("2026-01-01"), null),
        )
        assertEquals(ManagementOutcome.NotFound, outcome)
        assertEquals(0, jdbc.sql("SELECT count(*) FROM availability_rule").query(Int::class.java).single())
    }

    @Test
    fun `blocking time over an existing booking reports it instead of cancelling it`() {
        publishWorkingDay()
        val bookingRef = bookTheSlot()

        val outcome = management.addException(
            providerRef,
            "sub-owner",
            AvailabilityExceptionDraft(
                resourceRef = resourceRef,
                type = ExceptionType.BLOCK,
                startsAt = Instant.parse("2026-09-02T08:00:00Z"),
                endsAt = Instant.parse("2026-09-02T12:00:00Z"),
                reason = "equipment failure",
            ),
        )

        val applied = outcome as ManagementOutcome.Applied
        assertEquals(
            listOf(bookingRef), applied.conflicts.map { it.bookingRef },
            "R20: the provider is told which booking is now stranded",
        )
        assertEquals(
            "CONFIRMED",
            jdbc.sql("SELECT bs.code FROM booking b JOIN booking_state bs USING (booking_state_id)")
                .query(String::class.java).single(),
            "R20: the booking itself must be untouched",
        )
    }

    @Test
    fun `ending a rule retains it for audit rather than deleting it`() {
        val ruleRef = (publishWorkingDay() as ManagementOutcome.Applied).ref
        bookTheSlot()

        val outcome = management.endRule(providerRef, "sub-owner", ruleRef, LocalDate.parse("2026-09-01"))

        assertTrue(outcome is ManagementOutcome.Applied)
        assertEquals(
            1, jdbc.sql("SELECT count(*) FROM availability_rule").query(Int::class.java).single(),
            "R20: rules are ended, never removed",
        )
        assertEquals(
            LocalDate.parse("2026-09-01"),
            jdbc.sql("SELECT effective_until FROM availability_rule")
                .query(LocalDate::class.java).single(),
        )
        assertEquals(
            1, (outcome as ManagementOutcome.Applied).conflicts.size,
            "the booking now sits outside published availability and must be reported",
        )
    }
}

/** One JdbcClient for the integration tier, built through the real configuration. */
object JdbcClientHolder {
    val client = org.springframework.jdbc.core.simple.JdbcClient.create(
        JdbcConfig().jdbcTemplate(TestDatabase.dataSource),
    )
}
