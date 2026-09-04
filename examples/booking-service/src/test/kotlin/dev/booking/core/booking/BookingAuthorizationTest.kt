package dev.booking.core.booking

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tier 1 — R18, the one substantial rule the database cannot enforce, so it gets
 * direct tests rather than being covered only through the service.
 */
class BookingAuthorizationTest {

    private val booking = BookingIdentity(
        bookingId = 1,
        ref = UUID.randomUUID(),
        customerId = 10,
        providerId = 20,
        confirmationMode = ConfirmationMode.APPROVAL,
    )

    @Test
    fun `the owning customer acts as a customer`() {
        assertEquals(
            ActorType.CUSTOMER,
            BookingAuthorization.capacityOver(booking, callerCustomerId = 10, administeredProviderIds = emptySet()),
        )
    }

    @Test
    fun `an administrator of the owning provider acts as the provider`() {
        assertEquals(
            ActorType.PROVIDER,
            BookingAuthorization.capacityOver(booking, callerCustomerId = null, administeredProviderIds = setOf(20)),
        )
    }

    @Test
    fun `another customer has no capacity at all`() {
        assertNull(
            BookingAuthorization.capacityOver(booking, callerCustomerId = 11, administeredProviderIds = emptySet()),
        )
    }

    @Test
    fun `an administrator of a different provider has no capacity`() {
        assertNull(
            BookingAuthorization.capacityOver(booking, callerCustomerId = null, administeredProviderIds = setOf(21)),
        )
    }

    @Test
    fun `an anonymous caller has no capacity`() {
        assertNull(
            BookingAuthorization.capacityOver(booking, callerCustomerId = null, administeredProviderIds = emptySet()),
        )
    }

    @Test
    fun `provider capacity wins when the caller is both`() {
        assertEquals(
            ActorType.PROVIDER,
            BookingAuthorization.capacityOver(booking, callerCustomerId = 10, administeredProviderIds = setOf(20)),
            "the stronger capacity is the one their provider-side actions need",
        )
    }
}
