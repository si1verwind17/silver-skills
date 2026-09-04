package dev.booking.worker

import dev.booking.core.gate.GateOutcome
import dev.booking.core.gate.GateResolution
import dev.booking.core.gate.GateResolutionService
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Consumes gate resolutions (UC14).
 *
 * Pull consumption via a listener container, per AQ7 — the worker is a 24/7
 * Kubernetes resident, so it takes flow control rather than exposing an endpoint
 * for the broker to push at.
 *
 * A malformed message is a poison message, not a booking outcome: it is logged
 * and rethrown so the container's error handler can dead-letter it rather than
 * silently discarding a decision another service believes it made.
 */
@Component
@Profile("worker")
class GateResolutionListener(
    private val gates: GateResolutionService,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${booking.kafka.gate-resolution-topic:booking-gate-resolution-v1}"],
        groupId = "\${booking.kafka.group-id:booking-service}",
    )
    fun onMessage(payload: String) {
        val resolution = parse(payload)
        val handling = gates.handle(resolution)
        log.info(
            "gate resolution handled bookingRef={} gate={} outcome={} handling={}",
            resolution.bookingRef, resolution.holdReason, resolution.outcome, handling,
        )
    }

    private fun parse(payload: String): GateResolution {
        val node = mapper.readTree(payload)
        return GateResolution(
            messageId = requireNotNull(node.get("messageId")?.asString()) {
                "gate resolution has no messageId, so it cannot be deduplicated"
            },
            bookingRef = UUID.fromString(
                requireNotNull(node.get("bookingRef")?.asString()) {
                    "gate resolution names no booking"
                },
            ),
            holdReason = requireNotNull(node.get("holdReason")?.asString()) {
                "gate resolution names no gate"
            },
            outcome = GateOutcome.valueOf(
                requireNotNull(node.get("outcome")?.asString()) {
                    "gate resolution carries no outcome"
                },
            ),
            reason = node.get("reason")?.asString(),
            rawPayload = payload,
        )
    }
}
