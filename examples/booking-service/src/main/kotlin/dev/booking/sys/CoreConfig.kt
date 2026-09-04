package dev.booking.sys

import dev.booking.core.availability.AvailabilityQuery
import dev.booking.core.availability.AvailabilityService
import dev.booking.core.availability.ResourceSelector
import dev.booking.core.booking.BookingCatalog
import dev.booking.core.booking.BookingLifecycleService
import dev.booking.core.booking.BookingLookup
import dev.booking.core.booking.BookingTransitions
import dev.booking.core.booking.ProviderAdminDirectory
import dev.booking.core.listing.BookingListings
import dev.booking.core.listing.ListingService
import dev.booking.core.listing.ProviderLookup
import dev.booking.core.management.AvailabilityAdminRepository
import dev.booking.core.management.AvailabilityManagementService
import dev.booking.core.management.ProviderSetupRepository
import dev.booking.core.management.ProviderSetupService
import dev.booking.core.booking.BookingRepository
import dev.booking.core.booking.BookingService
import dev.booking.core.booking.CustomerDirectory
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the core services.  Business classes carry no Spring annotations — they
 * are plain constructor-injected objects, which is what lets the unit tests build
 * them directly with stubs and no container.
 */
@Configuration
class CoreConfig {

    @Bean
    fun bookingService(
        bookings: BookingRepository,
        catalog: BookingCatalog,
        resources: ResourceSelector,
        customers: CustomerDirectory,
        providerAdmins: ProviderAdminDirectory,
        clock: Clock,
        ids: IdGenerator,
    ): BookingService =
        BookingService(bookings, catalog, resources, customers, providerAdmins, clock, ids)

    @Bean
    fun bookingLifecycleService(
        lookup: BookingLookup,
        transitions: BookingTransitions,
        customers: CustomerDirectory,
        providerAdmins: ProviderAdminDirectory,
        clock: Clock,
        ids: IdGenerator,
    ): BookingLifecycleService =
        BookingLifecycleService(lookup, transitions, customers, providerAdmins, clock, ids)

    @Bean
    fun availabilityService(query: AvailabilityQuery): AvailabilityService =
        AvailabilityService(query)

    @Bean
    fun listingService(
        listings: BookingListings,
        customers: CustomerDirectory,
        providerAdmins: ProviderAdminDirectory,
        providers: ProviderLookup,
    ): ListingService = ListingService(listings, customers, providerAdmins, providers)

    @Bean
    fun availabilityManagementService(
        repository: AvailabilityAdminRepository,
        providers: ProviderLookup,
        providerAdmins: ProviderAdminDirectory,
        clock: Clock,
        ids: IdGenerator,
    ): AvailabilityManagementService =
        AvailabilityManagementService(repository, providers, providerAdmins, clock, ids)

    @Bean
    fun providerSetupService(
        repository: ProviderSetupRepository,
        providers: ProviderLookup,
        providerAdmins: ProviderAdminDirectory,
        ids: IdGenerator,
    ): ProviderSetupService = ProviderSetupService(repository, providers, providerAdmins, ids)
}
