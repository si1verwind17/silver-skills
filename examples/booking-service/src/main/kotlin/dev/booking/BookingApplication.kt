package dev.booking

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Entry point for both workloads.  Which one starts is decided by the active
 * Spring profile — `api` serves HTTP, `worker` runs the relay, the consumer and
 * the sweeps.  One image, two deployments, as architecture-design.md section 3
 * requires.
 */
@SpringBootApplication
class BookingApplication

fun main(args: Array<String>) {
    runApplication<BookingApplication>(*args)
}
