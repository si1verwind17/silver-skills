package dev.booking.core.booking

import dev.booking.core.availability.ResourceSelector
import dev.booking.sys.IdGenerator
import java.time.Clock

/**
 * Orchestrates booking creation (UC5, UC6).
 *
 * The steps are deliberately linear and individually named: resolve the service,
 * work out in what capacity the caller acts, resolve the resource, resolve the
 * customer, decide the gate, write.  Every invariant — capacity, overlap, buffers,
 * availability, lead time, horizon, idempotency — is enforced inside
 * `fn_create_booking`, so this class must not re-check any of them.
 */
class BookingService(
    private val bookings: BookingRepository,
    private val catalog: BookingCatalog,
    private val resources: ResourceSelector,
    private val customers: CustomerDirectory,
    private val providerAdmins: ProviderAdminDirectory,
    private val clock: Clock,
    private val ids: IdGenerator,
) {

    fun book(request: BookingRequest): BookingOutcome {
        val service = catalog.findServiceContext(request.serviceRef)
            ?: return BookingOutcome.Rejected(BookingRule.NOT_BOOKABLE, "no such service")

        // Capacity is derived from provider membership, never from a request
        // field — a caller cannot elect to be a provider and so cannot elect to
        // bypass R4's lead time.
        val actorType =
            if (service.providerId in providerAdmins.administeredProviderIds(request.actorSubject)) {
                ActorType.PROVIDER
            } else {
                ActorType.CUSTOMER
            }

        val customerId = resolveCustomer(request, actorType)
            ?: return BookingOutcome.NotFound

        val resourceId = resolveResource(request, service)
            ?: return BookingOutcome.Rejected(
                BookingRule.NOT_BOOKABLE,
                "no resource is available for that time",
            )

        return bookings.create(
            CreateBookingCommand(
                customerId = customerId,
                serviceId = service.serviceId,
                resourceId = resourceId,
                startsAt = request.startsAt,
                gate = GatePolicy.selectGate(service.confirmationMode),
                actorType = actorType,
                actorSubject = request.actorSubject,
                idempotencyKey = request.idempotencyKey,
                now = clock.instant(),
                bookingRef = ids.newId(),
                eventId = ids.newId(),
            ),
        )
    }

    /**
     * A provider may book for a named customer; anyone else naming one is refused
     * as though the customer did not exist, because confirming that a reference is
     * valid would itself leak (R18).
     */
    private fun resolveCustomer(request: BookingRequest, actorType: ActorType): Long? =
        when {
            request.onBehalfOfCustomerRef == null ->
                customers.findOrCreate(request.customerSubject, request.customerContact)
            actorType == ActorType.PROVIDER ->
                customers.findIdByRef(request.onBehalfOfCustomerRef)
            else -> null
        }

    /**
     * An explicit preference is honoured as given — if it turns out to be
     * ineligible the database says so (BK008), which keeps one authority for
     * eligibility.  Only absence triggers selection (PD14).
     */
    private fun resolveResource(request: BookingRequest, service: ServiceContext): Long? =
        // Deliberately an if, not an elvis chain: an explicit reference that does
        // not resolve must fail, never fall through to picking a different
        // resource than the customer asked for.
        if (request.resourceRef != null) {
            catalog.findResourceId(request.resourceRef)
        } else {
            resources.selectFor(service.serviceId, request.startsAt)
        }
}
