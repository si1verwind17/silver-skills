package dev.booking.worker

import dev.booking.core.outbox.OutboxRelay
import dev.booking.core.sweep.SweepService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drives the relay (UC13).  A tight interval because dispatch lag is an SLO
 * (NF11), and the scan is cheap: its index holds only the undispatched backlog.
 */
@Component
@Profile("worker")
class OutboxRelaySchedule(private val relay: OutboxRelay) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${booking.relay.interval-ms:500}")
    fun dispatch() {
        val report = relay.dispatchDue()
        // Silence when idle: a log line per empty scan twice a second would bury
        // everything else.  Failures are logged individually by the relay.
        if (report.attempted > 0) {
            log.info("outbox dispatched published={} failed={}", report.published, report.failed)
        }
    }
}

/**
 * Drives the two sweeps (UC11, UC12).  Safe on every replica simultaneously —
 * both database functions use SKIP LOCKED and are idempotent.
 */
@Component
@Profile("worker")
class SweepSchedule(private val sweeps: SweepService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${booking.sweep.expiry-interval-ms:30000}")
    fun expireLapsedHolds() {
        val expired = sweeps.expireLapsedHolds()
        if (expired > 0) log.info("holds expired count={}", expired)
    }

    @Scheduled(fixedDelayString = "\${booking.sweep.completion-interval-ms:3600000}")
    fun autoCompletePastBookings() {
        val completed = sweeps.autoCompletePastBookings()
        if (completed > 0) log.info("bookings auto-completed count={}", completed)
    }
}
