package dev.booking.core.booking

import dev.booking.core.availability.ResourceSelector
import dev.booking.sys.IdGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tier 2 — creation orchestration with every port stubbed. */
class BookingServiceTest {

    private val now = Instant.parse("2026-09-01T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val serviceRef = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val resourceRef = UUID.fromString("00000000-0000-7000-8000-000000000002")
    private val customerRef = UUID.fromString("00000000-0000-7000-8000-000000000003")

    private class RecordingRepository : BookingRepository {
        var received: CreateBookingCommand? = null
        override fun create(command: CreateBookingCommand): BookingOutcome {
            received = command
            return BookingOutcome.Created(
                CreatedBooking(command.bookingRef, BookingState.CONFIRMED, wasReplay = false),
            )
        }
    }

    private class StubCatalog(
        private val context: ServiceContext?,
        private val resourceId: Long? = 3L,
    ) : BookingCatalog {
        override fun findServiceContext(serviceRef: UUID) = context
        override fun findResourceId(resourceRef: UUID) = resourceId
    }

    private class StubSelector(private val chosen: Long?) : ResourceSelector {
        var calls = 0
        override fun selectFor(serviceId: Long, startsAt: Instant): Long? {
            calls++
            return chosen
        }
    }

    private class StubCustomers(private val byRef: Long? = 99L) : CustomerDirectory {
        var createdFor: String? = null
        override fun findOrCreate(idpSubject: String, contact: CustomerContact): Long {
            createdFor = idpSubject
            return 77L
        }
        override fun findIdBySubject(idpSubject: String) = 77L
        override fun findIdByRef(customerRef: UUID) = byRef
    }

    private class StubAdmins(private val ids: Set<Long>) : ProviderAdminDirectory {
        override fun administeredProviderIds(idpSubject: String) = ids
    }

    private class SequentialIds(private vararg val ids: UUID) : IdGenerator {
        private var index = 0
        override fun newId(): UUID = ids[index++]
    }

    private fun request(
        resource: UUID? = resourceRef,
        onBehalfOf: UUID? = null,
        idempotencyKey: String? = "key-1",
    ) = BookingRequest(
        serviceRef = serviceRef,
        resourceRef = resource,
        startsAt = Instant.parse("2026-09-02T09:00:00Z"),
        actorSubject = "sub-alice",
        customerSubject = "sub-alice",
        customerContact = CustomerContact("Alice", "a@x.test", null),
        idempotencyKey = idempotencyKey,
        onBehalfOfCustomerRef = onBehalfOf,
    )

    private fun context(mode: ConfirmationMode = ConfirmationMode.APPROVAL) =
        ServiceContext(serviceId = 1, providerId = 2, confirmationMode = mode)

    private fun service(
        repo: BookingRepository,
        catalog: BookingCatalog,
        selector: ResourceSelector = StubSelector(3L),
        customers: CustomerDirectory = StubCustomers(),
        admins: ProviderAdminDirectory = StubAdmins(emptySet()),
    ) = BookingService(
        repo, catalog, selector, customers, admins, clock,
        SequentialIds(
            UUID.fromString("00000000-0000-7000-8000-0000000000aa"),
            UUID.fromString("00000000-0000-7000-8000-0000000000bb"),
        ),
    )

    @Test
    fun `an unknown service is rejected without touching the database`() {
        val repo = RecordingRepository()
        val outcome = service(repo, StubCatalog(null)).book(request())

        assertEquals(BookingRule.NOT_BOOKABLE, (outcome as BookingOutcome.Rejected).rule)
        assertNull(repo.received)
    }

    @Test
    fun `an approval-mode provider produces a gated command and instant does not`() {
        val gated = RecordingRepository()
        service(gated, StubCatalog(context(ConfirmationMode.APPROVAL))).book(request())
        assertEquals(HoldReason.AWAITING_PROVIDER_APPROVAL, gated.received!!.gate)

        val ungated = RecordingRepository()
        service(ungated, StubCatalog(context(ConfirmationMode.INSTANT))).book(request())
        assertNull(ungated.received!!.gate)
    }

    @Test
    fun `time and identifiers come from the injected clock and generator`() {
        val repo = RecordingRepository()
        service(repo, StubCatalog(context())).book(request())

        val command = repo.received!!
        assertEquals(now, command.now, "no inline now() on the write path")
        assertEquals(UUID.fromString("00000000-0000-7000-8000-0000000000aa"), command.bookingRef)
        assertEquals(UUID.fromString("00000000-0000-7000-8000-0000000000bb"), command.eventId)
    }

    @Test
    fun `an explicit resource that does not resolve is refused, never silently replaced`() {
        val repo = RecordingRepository()
        val selector = StubSelector(3L)
        val outcome = service(repo, StubCatalog(context(), resourceId = null), selector)
            .book(request(resource = resourceRef))

        assertEquals(BookingRule.NOT_BOOKABLE, (outcome as BookingOutcome.Rejected).rule)
        assertEquals(0, selector.calls, "an unresolvable preference must not fall back to selection")
        assertNull(repo.received)
    }

    @Test
    fun `no preference triggers deterministic selection`() {
        val repo = RecordingRepository()
        val selector = StubSelector(42L)
        service(repo, StubCatalog(context()), selector).book(request(resource = null))

        assertEquals(1, selector.calls)
        assertEquals(42L, repo.received!!.resourceId)
    }

    @Test
    fun `when nothing is free the booking is refused rather than guessed`() {
        val repo = RecordingRepository()
        val outcome = service(repo, StubCatalog(context()), StubSelector(null))
            .book(request(resource = null))

        assertEquals(BookingRule.NOT_BOOKABLE, (outcome as BookingOutcome.Rejected).rule)
    }

    @Test
    fun `a caller who administers the provider acts as the provider`() {
        val repo = RecordingRepository()
        service(repo, StubCatalog(context()), admins = StubAdmins(setOf(2L))).book(request())

        assertEquals(
            ActorType.PROVIDER, repo.received!!.actorType,
            "capacity is derived from membership, so R4's lead time bypass cannot be self-declared",
        )
    }

    @Test
    fun `a customer cannot book on someone else's behalf`() {
        val repo = RecordingRepository()
        val outcome = service(repo, StubCatalog(context()))
            .book(request(onBehalfOf = customerRef))

        assertTrue(outcome is BookingOutcome.NotFound, "R18: refused as absence, not as forbidden")
        assertNull(repo.received)
    }

    @Test
    fun `a provider may book on a named customer's behalf`() {
        val repo = RecordingRepository()
        service(repo, StubCatalog(context()), admins = StubAdmins(setOf(2L)))
            .book(request(onBehalfOf = customerRef))

        assertEquals(99L, repo.received!!.customerId)
    }
}
