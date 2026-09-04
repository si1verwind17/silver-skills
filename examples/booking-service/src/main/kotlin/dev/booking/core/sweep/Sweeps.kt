package dev.booking.core.sweep

import java.time.Clock
import java.time.Instant

/**
 * The two scheduled jobs (UC11, UC12).  Both are single database calls because
 * the functions are idempotent and safe to run concurrently — several worker
 * replicas sweeping at once is expected, not merely tolerated.
 */
interface SweepRepository {
    fun expireHolds(now: Instant, limit: Int): Int
    fun autoComplete(now: Instant, limit: Int): Int
}

class SweepService(
    private val sweeps: SweepRepository,
    private val clock: Clock,
    private val batchSize: Int,
) {
    fun expireLapsedHolds(): Int = sweeps.expireHolds(clock.instant(), batchSize)

    fun autoCompletePastBookings(): Int = sweeps.autoComplete(clock.instant(), batchSize)
}
