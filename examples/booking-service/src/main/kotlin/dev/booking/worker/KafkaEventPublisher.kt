package dev.booking.worker

import dev.booking.core.outbox.EventPublisher
import dev.booking.core.outbox.PendingEvent
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Publishes the booking lifecycle stream (architecture-design.md section 6).
 *
 * Keyed by booking reference, which is what gives per-booking ordering (R29) —
 * the key is not decorative, it is the ordering guarantee.
 *
 * The envelope is assembled here rather than stored: the database already built
 * the payload inside `fn_emit_event`, so this adds only what belongs to delivery.
 */
@Component
@Profile("worker")
class KafkaEventPublisher(
    private val kafka: KafkaTemplate<String, String>,
    private val mapper: ObjectMapper,
    @Value("\${booking.kafka.lifecycle-topic:booking-lifecycle-v1}") private val topic: String,
    @Value("\${booking.kafka.send-timeout-seconds:10}") private val timeoutSeconds: Long,
) : EventPublisher {

    override fun publish(event: PendingEvent) {
        val envelope = mapper.createObjectNode().apply {
            put("eventId", event.eventId.toString())
            put("eventType", event.eventType)
            put("schemaVersion", event.schemaVersion)
            put("occurredAt", event.occurredAt.toString())
            // Jackson 3 dropped the generic on set(); the payload is embedded as a
            // parsed node so the envelope stays valid JSON rather than a nested string.
            set("payload", mapper.readTree(event.payload))
        }
        // Blocking on the acknowledgement is deliberate: the row must not be marked
        // dispatched until the broker has actually accepted it (R22).
        kafka.send(topic, event.bookingRef.toString(), mapper.writeValueAsString(envelope))
            .get(timeoutSeconds, TimeUnit.SECONDS)
    }
}
