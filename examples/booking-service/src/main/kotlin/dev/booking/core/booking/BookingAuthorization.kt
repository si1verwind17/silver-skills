package dev.booking.core.booking

/**
 * Decides in what capacity a caller acts on a booking (R18).
 *
 * This lives in the application rather than the database on purpose:
 * `fn_transition_booking` checks the actor *type* a transition requires, but has
 * no idea *which* customer or which provider's administrator is calling. Tenant
 * scoping is the one substantial rule the database cannot own, which is why it is
 * a standalone pure function with its own tests.
 *
 * Deriving the capacity from the booking, rather than from a token claim, also
 * sidesteps PD1 being open: membership of `provider_admin` is the authority, and
 * a subject who is neither the owner nor an administrator gets no capacity at all.
 */
object BookingAuthorization {

    /**
     * The capacity this caller has over this booking, or null if none.
     *
     * Provider administration is checked first: a person who both owns a booking
     * and administers its provider is treated as the provider, because that is the
     * stronger capacity and the one their provider-side actions need.
     *
     * Callers must translate null into [BookingOutcome.NotFound], never into a
     * distinct "forbidden" answer — R18 requires that another tenant's booking be
     * indistinguishable from one that does not exist.
     */
    fun capacityOver(
        booking: BookingIdentity,
        callerCustomerId: Long?,
        administeredProviderIds: Set<Long>,
    ): ActorType? = when {
        booking.providerId in administeredProviderIds -> ActorType.PROVIDER
        callerCustomerId != null && callerCustomerId == booking.customerId -> ActorType.CUSTOMER
        else -> null
    }
}
