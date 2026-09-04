package dev.booking.worker

import dev.booking.core.booking.BookingLookup
import dev.booking.core.booking.BookingTransitions
import dev.booking.core.gate.GateRejections
import dev.booking.core.gate.GateResolutionService
import dev.booking.core.gate.InboxRepository
import dev.booking.core.outbox.EventPublisher
import dev.booking.core.outbox.OutboxRelay
import dev.booking.core.outbox.OutboxRepository
import dev.booking.core.sweep.SweepRepository
import dev.booking.core.sweep.SweepService
import dev.booking.sys.IdGenerator
import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The worker workload: relay, sweeps and the gate consumer.
 *
 * Profile-gated so the same image serves both deployments — `api` pods do not run
 * the relay, and worker pods do not serve HTTP (architecture-design.md section 3).
 */
@Configuration
@Profile("worker")
@EnableScheduling
class WorkerConfig {

    @Bean
    fun outboxRelay(
        outbox: OutboxRepository,
        publisher: EventPublisher,
        clock: Clock,
        @Value("\${booking.relay.batch-size:100}") batchSize: Int,
    ): OutboxRelay = OutboxRelay(outbox, publisher, clock, batchSize)

    @Bean
    fun sweepService(
        sweeps: SweepRepository,
        clock: Clock,
        @Value("\${booking.sweep.batch-size:500}") batchSize: Int,
    ): SweepService = SweepService(sweeps, clock, batchSize)

    @Bean
    fun gateResolutionService(
        inbox: InboxRepository,
        lookup: BookingLookup,
        transitions: BookingTransitions,
        rejections: GateRejections,
        clock: Clock,
        ids: IdGenerator,
    ): GateResolutionService =
        GateResolutionService(inbox, lookup, transitions, rejections, clock, ids)
}
