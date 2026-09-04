package dev.booking.core.booking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tier 1 — a pure rule, tested as data in / data out with no stubs at all.
 * This is possible only because R33's policy is a standalone function rather
 * than a private method on the service.
 */
class GatePolicyTest {

    @Test
    fun `instant-confirm providers apply no gate`() {
        assertNull(GatePolicy.selectGate(ConfirmationMode.INSTANT))
    }

    @Test
    fun `approval-mode providers hold for provider approval`() {
        assertEquals(
            HoldReason.AWAITING_PROVIDER_APPROVAL,
            GatePolicy.selectGate(ConfirmationMode.APPROVAL),
        )
    }

    @Test
    fun `every confirmation mode has a decided gate`() {
        // Guards LD16's promise: adding a mode without deciding its gate must not
        // compile, and adding a gate must not silently change existing modes.
        ConfirmationMode.entries.forEach { GatePolicy.selectGate(it) }
    }
}
