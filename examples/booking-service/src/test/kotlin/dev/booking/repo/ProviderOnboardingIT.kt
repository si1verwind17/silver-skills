package dev.booking.repo

import dev.booking.core.availability.AvailabilityService
import dev.booking.core.booking.BookingOutcome
import dev.booking.core.booking.BookingRequest
import dev.booking.core.booking.BookingService
import dev.booking.core.booking.BookingState
import dev.booking.core.booking.ConfirmationMode
import dev.booking.core.booking.CustomerContact
import dev.booking.core.availability.AvailabilityOutcome
import dev.booking.core.management.AvailabilityManagementService
import dev.booking.core.management.AvailabilityRuleDraft
import dev.booking.core.management.ManagementOutcome
import dev.booking.core.management.ProviderDraft
import dev.booking.core.management.ProviderSetupService
import dev.booking.core.management.ResourceDraft
import dev.booking.core.management.ServiceDraft
import dev.booking.core.management.SetupOutcome
import dev.booking.sys.IdGenerator
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
import kotlin.test.assertTrue

/**
 * Tier 3 — UC1 through UC5 from an empty database.
 *
 * The narrowest tests each prove one rule; this one proves the pieces fit: a
 * provider onboards itself, publishes availability, and a customer books a slot
 * that availability search actually offered. A schema or wiring mistake that the
 * focused tests each step around would surface here.
 */
class ProviderOnboardingIT {

    private val now = Instant.parse("2026-09-01T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ids: IdGenerator = UuidV7Generator(clock, RandomGenerator.getDefault())

    private val jdbc = JdbcClientHolder.client
    private val sql = SqlSource()
    private val listings = JdbcListingRepository(jdbc, sql)
    private val admins = JdbcProviderAdminDirectory(jdbc, sql)
    private val setup = ProviderSetupService(JdbcProviderSetupRepository(jdbc, sql), listings, admins, ids)
    private val management = AvailabilityManagementService(
        JdbcAvailabilityAdminRepository(jdbc, sql), listings, admins, clock, ids,
    )
    private val availabilityRepo = JdbcAvailabilityRepository(jdbc, sql)
    private val availability = AvailabilityService(availabilityRepo)
    private val bookings = BookingService(
        JdbcBookingRepository(jdbc, sql),
        JdbcBookingRepository(jdbc, sql),
        availabilityRepo,
        JdbcCustomerDirectory(jdbc, ids, clock, sql),
        admins,
        clock,
        ids,
    )

    @BeforeTest
    fun emptyDatabase() {
        listOf(
            "outbox_event", "inbox_message", "booking_transition", "booking", "session",
            "availability_exception", "availability_rule", "service_resource", "service",
            "resource", "provider_admin", "customer", "provider",
        ).forEach { jdbc.sql("DELETE FROM $it").update() }
    }

    @Test
    fun `a provider can onboard itself and take a booking`() {
        val owner = "sub-owner"

        val providerRef = (
            setup.createProvider(
                owner,
                ProviderDraft(
                    name = "Onboarded Clinic",
                    timezone = "UTC",
                    confirmationMode = ConfirmationMode.INSTANT,
                    minLeadMinutes = null,
                    bookingHorizonDays = null,
                    cancellationWindowMinutes = null,
                    approvalHoldTtlMinutes = null,
                    autoCompleteGraceDays = null,
                ),
            ) as SetupOutcome.Created
            ).ref

        // The creator becomes the first administrator — otherwise the provider
        // would exist with nobody able to manage it.
        val resourceRef = (setup.addResource(providerRef, owner, ResourceDraft("Dr Who", null)) as SetupOutcome.Created).ref
        val serviceRef = (
            setup.addService(
                providerRef, owner,
                ServiceDraft("Consultation", durationMinutes = 30, capacity = null,
                    bufferBeforeMinutes = null, bufferAfterMinutes = 15, slotStepMinutes = 30),
            ) as SetupOutcome.Created
            ).ref
        assertEquals(SetupOutcome.Linked, setup.makeEligible(providerRef, owner, serviceRef, resourceRef))

        val published = management.publishRule(
            providerRef, owner,
            AvailabilityRuleDraft(
                resourceRef = resourceRef,
                dayOfWeek = LocalDate.parse("2026-09-02").dayOfWeek.value % 7,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(12, 0),
                effectiveFrom = LocalDate.parse("2026-01-01"),
                effectiveUntil = null,
            ),
        )
        assertTrue(published is ManagementOutcome.Applied)

        val offered = availability.search(
            providerRef, serviceRef,
            Instant.parse("2026-09-02T00:00:00Z"),
            Instant.parse("2026-09-03T00:00:00Z"),
            null,
        ) as AvailabilityOutcome.Found
        assertTrue(offered.slots.isNotEmpty(), "a published day must offer slots")
        val first = offered.slots.first()
        assertEquals(Instant.parse("2026-09-02T09:00:00Z"), first.startsAt)

        // No resource preference: selection must pick the only eligible one (PD14).
        val outcome = bookings.book(
            BookingRequest(
                serviceRef = serviceRef,
                resourceRef = null,
                startsAt = first.startsAt,
                actorSubject = "sub-patient",
                customerSubject = "sub-patient",
                customerContact = CustomerContact("Pat", "pat@x.test", null),
                idempotencyKey = "onboard-1",
            ),
        )

        val created = (outcome as BookingOutcome.Created).booking
        assertEquals(BookingState.CONFIRMED, created.state, "an instant-confirm provider confirms outright")

        // The slot just taken, and the one inside its 15-minute buffer, are gone.
        val afterwards = (
            availability.search(
                providerRef, serviceRef,
                Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"),
                null,
            ) as AvailabilityOutcome.Found
            ).slots.map { it.startsAt }
        assertTrue(Instant.parse("2026-09-02T09:00:00Z") !in afterwards, "the booked slot is no longer offered")
        assertTrue(Instant.parse("2026-09-02T09:30:00Z") !in afterwards, "R31: the buffer removes the next slot too")
        assertTrue(Instant.parse("2026-09-02T10:00:00Z") in afterwards, "the slot clear of the buffer is still offered")
    }

    @Test
    fun `a subject who did not create the provider cannot configure it`() {
        val providerRef = (
            setup.createProvider(
                "sub-owner",
                ProviderDraft("Clinic", "UTC", ConfirmationMode.INSTANT, null, null, null, null, null),
            ) as SetupOutcome.Created
            ).ref

        assertEquals(
            SetupOutcome.NotFound,
            setup.addResource(providerRef, "sub-stranger", ResourceDraft("Ghost", null)),
        )
        assertEquals(0, jdbc.sql("SELECT count(*) FROM resource").query(Int::class.java).single())
    }

    @Test
    fun `an unrecognised timezone is refused by the database, not stored`() {
        val failure = runCatching {
            setup.createProvider(
                "sub-owner",
                ProviderDraft("Bad TZ", "Not/AZone", ConfirmationMode.INSTANT, null, null, null, null, null),
            )
        }
        assertTrue(failure.isFailure, "R14: the timezone CHECK is the authority, not application validation")
        assertEquals(0, jdbc.sql("SELECT count(*) FROM provider").query(Int::class.java).single())
    }
}
