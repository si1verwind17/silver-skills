package dev.booking.repo

import dev.booking.core.management.ProviderDraft
import dev.booking.core.management.ProviderSetupRepository
import dev.booking.core.management.ResourceDraft
import dev.booking.core.management.ServiceDraft
import dev.booking.sys.SqlSource
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** UC1 writes.  Every statement is scoped to a provider the caller administers. */
@Repository
class JdbcProviderSetupRepository(
    private val jdbc: JdbcClient,
    sql: SqlSource,
) : ProviderSetupRepository {

    private val insertProviderSql = sql.load("management/insert_provider")
    private val insertAdminSql = sql.load("management/insert_provider_admin")
    private val insertResourceSql = sql.load("management/insert_resource")
    private val insertServiceSql = sql.load("management/insert_service")
    private val linkSql = sql.load("management/link_service_resource")

    override fun insertProvider(publicRef: UUID, draft: ProviderDraft): Long =
        jdbc.sql(insertProviderSql)
            .param("publicRef", publicRef)
            .param("name", draft.name)
            .param("timezone", draft.timezone)
            .param("confirmationMode", draft.confirmationMode.name)
            .param("minLeadMinutes", draft.minLeadMinutes)
            .param("bookingHorizonDays", draft.bookingHorizonDays)
            .param("cancellationWindowMinutes", draft.cancellationWindowMinutes)
            .param("approvalHoldTtlMinutes", draft.approvalHoldTtlMinutes)
            .param("autoCompleteGraceDays", draft.autoCompleteGraceDays)
            .query(Long::class.java)
            .single()

    override fun addAdmin(providerId: Long, idpSubject: String) {
        jdbc.sql(insertAdminSql)
            .param("providerId", providerId)
            .param("idpSubject", idpSubject)
            .update()
    }

    override fun insertResource(providerId: Long, publicRef: UUID, draft: ResourceDraft): Long =
        jdbc.sql(insertResourceSql)
            .param("publicRef", publicRef)
            .param("providerId", providerId)
            .param("name", draft.name)
            .param("idpSubject", draft.linkedSubject)
            .query(Long::class.java)
            .single()

    override fun insertService(providerId: Long, publicRef: UUID, draft: ServiceDraft): Long =
        jdbc.sql(insertServiceSql)
            .param("publicRef", publicRef)
            .param("providerId", providerId)
            .param("name", draft.name)
            .param("durationMinutes", draft.durationMinutes)
            .param("capacity", draft.capacity)
            .param("bufferBeforeMinutes", draft.bufferBeforeMinutes)
            .param("bufferAfterMinutes", draft.bufferAfterMinutes)
            .param("slotStepMinutes", draft.slotStepMinutes)
            .query(Long::class.java)
            .single()

    override fun linkServiceResource(
        providerId: Long,
        serviceRef: UUID,
        resourceRef: UUID,
    ): Boolean =
        jdbc.sql(linkSql)
            .param("providerId", providerId)
            .param("serviceRef", serviceRef)
            .param("resourceRef", resourceRef)
            .update() == 1
}
