package dev.booking.sys

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * NF7: every log line carries a correlation id.
 *
 * An inbound id is honoured so a request can be followed across services; absent
 * one, a fresh identifier is minted rather than leaving the field empty, because
 * a blank correlation id is worse than a synthetic one — it silently breaks the
 * join at 3am.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter(private val ids: IdGenerator) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(HEADER)?.take(MAX_LENGTH)?.takeIf { it.isNotBlank() }
            ?: ids.newId().toString()
        MDC.put(KEY, correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // Virtual threads are pooled by the runtime, so a stale MDC entry would
            // attach itself to whatever request runs next.
            MDC.remove(KEY)
        }
    }

    private companion object {
        const val HEADER = "X-Correlation-Id"
        const val KEY = "correlationId"
        const val MAX_LENGTH = 128
    }
}
