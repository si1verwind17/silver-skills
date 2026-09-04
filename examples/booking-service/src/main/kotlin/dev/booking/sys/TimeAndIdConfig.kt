package dev.booking.sys

import java.time.Clock
import java.util.random.RandomGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Clock and identifier generation are beans so that no business code ever calls
 * `Instant.now()` or generates a UUID inline.  Every timestamp this service
 * writes is passed explicitly into a database function as `p_now`, which is what
 * makes the lifecycle testable at arbitrary points in time.
 */
@Configuration
class TimeAndIdConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun randomGenerator(): RandomGenerator = RandomGenerator.getDefault()

    @Bean
    fun idGenerator(clock: Clock, random: RandomGenerator): IdGenerator =
        UuidV7Generator(clock, random)
}
