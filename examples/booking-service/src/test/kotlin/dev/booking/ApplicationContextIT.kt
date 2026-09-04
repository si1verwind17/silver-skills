package dev.booking

import dev.booking.api.BookingController
import dev.booking.core.outbox.OutboxRelay
import dev.booking.core.sweep.SweepService
import dev.booking.repo.TestDatabase
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Boots the real application context for both workloads.
 *
 * Every other test constructs its collaborators directly, which is what makes
 * them fast — but it also means a wiring mistake (a missing bean, two candidates
 * for one port) would never surface. This is the test that catches that, and it
 * is the reason the `api` and `worker` profiles are both exercised.
 */
@SpringBootTest
@ActiveProfiles("api", "worker")
class ApplicationContextIT {

    @TestConfiguration
    class StubIdp {
        /**
         * Replaces the IdP so the context can start without one.  Deliberately not
         * a permissive security config: the decoder is stubbed, the filter chain is
         * the real one.
         */
        @Bean
        fun jwtDecoder(): JwtDecoder = JwtDecoder { token ->
            Jwt.withTokenValue(token).header("alg", "none").subject("sub-test").build()
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            val url = System.getenv("BOOKING_TEST_JDBC_URL")
                ?: error("integration database not configured")
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("BOOKING_TEST_DB_USER") }
            registry.add("spring.datasource.password") { System.getenv("BOOKING_TEST_DB_PASSWORD") }
            registry.add("spring.kafka.bootstrap-servers") { "localhost:9092" }
            // Wiring is under test, not connectivity: starting the listener would
            // make the context wait on a broker that is not running here.
            registry.add("spring.kafka.listener.auto-startup") { "false" }
            // Touch the shared datasource so migrations run once, consistently with
            // the other integration tests.
            TestDatabase.dataSource
        }
    }

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `the api workload wires end to end`() {
        assertNotNull(context.getBean(BookingController::class.java))
    }

    @Test
    fun `the worker workload wires end to end`() {
        assertNotNull(context.getBean(OutboxRelay::class.java))
        assertNotNull(context.getBean(SweepService::class.java))
    }

    @Test
    fun `every port has exactly one implementation`() {
        listOf(
            dev.booking.core.booking.BookingRepository::class.java,
            dev.booking.core.booking.BookingCatalog::class.java,
            dev.booking.core.booking.BookingLookup::class.java,
            dev.booking.core.booking.BookingTransitions::class.java,
            dev.booking.core.booking.CustomerDirectory::class.java,
            dev.booking.core.booking.ProviderAdminDirectory::class.java,
            dev.booking.core.availability.AvailabilityQuery::class.java,
            dev.booking.core.availability.ResourceSelector::class.java,
            dev.booking.core.listing.BookingListings::class.java,
            dev.booking.core.listing.ProviderLookup::class.java,
            dev.booking.core.outbox.OutboxRepository::class.java,
            dev.booking.core.outbox.EventPublisher::class.java,
            dev.booking.core.gate.InboxRepository::class.java,
            dev.booking.core.gate.GateRejections::class.java,
        ).forEach { port ->
            val beans = context.getBeanNamesForType(port)
            kotlin.test.assertEquals(
                1, beans.size,
                "${port.simpleName} should have exactly one implementation, found ${beans.toList()}",
            )
        }
    }
}
