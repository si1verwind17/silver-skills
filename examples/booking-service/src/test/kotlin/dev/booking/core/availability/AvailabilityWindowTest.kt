package dev.booking.core.availability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AvailabilityWindowTest {

    private val from = Instant.parse("2026-09-01T00:00:00Z")

    @Test
    fun `a window inside the limit is accepted`() {
        assertNull(AvailabilityWindow.validate(from, from.plus(AvailabilityWindow.maximumSpan)))
    }

    @Test
    fun `a window beyond the limit is rejected rather than truncated`() {
        // UC4: a truncated answer looks exactly like a complete one, which is the
        // failure worth preventing.
        assertNotNull(
            AvailabilityWindow.validate(
                from,
                from.plus(AvailabilityWindow.maximumSpan).plusSeconds(1),
            ),
        )
    }

    @Test
    fun `a backwards or empty window is rejected`() {
        assertNotNull(AvailabilityWindow.validate(from, from))
        assertNotNull(AvailabilityWindow.validate(from, from.minusSeconds(1)))
    }
}
