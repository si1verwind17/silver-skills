package dev.booking.sys

import java.time.Clock
import java.util.UUID
import java.util.random.RandomGenerator

/**
 * Supplies the public identifiers this service mints.  Injected rather than
 * called statically so tests can make identifier generation deterministic —
 * the same reason [Clock] is injected (backend-code-conventions, "Testing").
 */
interface IdGenerator {
    fun newId(): UUID
}

/**
 * Time-ordered UUIDv7, as data-design.md requires for every `public_ref` and
 * for `outbox_event.event_id`.  Version 7 rather than 4 so inserts land near
 * the right-hand edge of the index instead of scattering across it.
 */
class UuidV7Generator(
    private val clock: Clock,
    private val random: RandomGenerator,
) : IdGenerator {

    override fun newId(): UUID {
        val millis = clock.millis()
        val randA = random.nextInt(1 shl 12).toLong()
        val mostSignificant = (millis shl 16) or (VERSION_7 shl 12) or randA
        // Variant bits are 10 in the two highest bits of the low half.
        val leastSignificant = (random.nextLong() and VARIANT_MASK) or VARIANT_BITS
        return UUID(mostSignificant, leastSignificant)
    }

    private companion object {
        const val VERSION_7 = 0x7L
        const val VARIANT_MASK = 0x3FFFFFFFFFFFFFFFL
        const val VARIANT_BITS = Long.MIN_VALUE // 0x8000000000000000
    }
}
