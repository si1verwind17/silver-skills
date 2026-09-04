package dev.booking.sys

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.random.RandomGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7GeneratorTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-09-01T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `generates version 7 variant 2 identifiers`() {
        val id = UuidV7Generator(fixedClock, RandomGenerator.getDefault()).newId()
        assertEquals(7, id.version(), "must be UUIDv7 so inserts stay index-local")
        assertEquals(2, id.variant(), "RFC 4122 variant bits")
    }

    @Test
    fun `embeds the injected clock reading, not the wall clock`() {
        val id = UuidV7Generator(fixedClock, RandomGenerator.getDefault()).newId()
        val embeddedMillis = id.mostSignificantBits ushr 16
        assertEquals(fixedClock.millis(), embeddedMillis)
    }

    @Test
    fun `later timestamps sort after earlier ones`() {
        val early = UuidV7Generator(fixedClock, RandomGenerator.getDefault()).newId()
        val later = UuidV7Generator(
            Clock.fixed(Instant.parse("2026-09-01T10:15:31Z"), ZoneOffset.UTC),
            RandomGenerator.getDefault(),
        ).newId()
        assertTrue(
            (early.mostSignificantBits ushr 16) < (later.mostSignificantBits ushr 16),
            "time ordering is the whole reason for choosing v7 over v4",
        )
    }
}
