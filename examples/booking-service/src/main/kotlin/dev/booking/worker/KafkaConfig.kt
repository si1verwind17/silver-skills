package dev.booking.worker

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

/**
 * A String-keyed, String-valued template for the lifecycle stream.
 *
 * Spring Boot auto-configures `KafkaTemplate<Object, Object>`, which does not
 * satisfy a `KafkaTemplate<String, String>` dependency — the worker would fail to
 * start. Declaring the typed template here also pins the wire format explicitly:
 * the key is a booking reference and the value is the JSON envelope, both plain
 * strings, so no schema-registry serializer is required (stack-selection.md
 * section 6.1 lists that as deliberately not needed).
 */
@Configuration
@Profile("worker")
class KafkaConfig {

    @Bean
    fun bookingKafkaTemplate(properties: KafkaProperties): KafkaTemplate<String, String> {
        val configs = properties.buildProducerProperties().toMutableMap()
        configs[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        configs[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(configs))
    }
}
