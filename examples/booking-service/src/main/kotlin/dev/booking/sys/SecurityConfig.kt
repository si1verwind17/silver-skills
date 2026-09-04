package dev.booking.sys

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * R17: identity comes only from a verified token, and nothing else.
 *
 * There is deliberately no fallback for a missing issuer configuration — Boot
 * requires a `JwtDecoder`, so a deployment without one fails at startup instead
 * of quietly accepting unverified requests.  A misconfigured deployment that
 * dies loudly is the intended behaviour.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/**").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { } }
            .build()
}
