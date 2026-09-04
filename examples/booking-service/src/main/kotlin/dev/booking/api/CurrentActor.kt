package dev.booking.api

import dev.booking.core.booking.CustomerContact
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

/**
 * Who is calling, as far as the token can say (R17).
 *
 * Deliberately carries no role: what a caller may do is derived from
 * `provider_admin` membership per booking, not asserted by a claim. That keeps
 * PD1 genuinely open — settling the claim mapping later changes this class and
 * nothing else.
 */
data class Actor(
    val subject: String,
    val contact: CustomerContact,
)

interface CurrentActor {
    fun require(): Actor
}

@Component
class JwtCurrentActor : CurrentActor {

    override fun require(): Actor {
        val jwt = SecurityContextHolder.getContext().authentication?.principal as? Jwt
            ?: error("no verified token on the security context — the endpoint is not protected")
        // A verified token without a subject is not a valid identity.  Failing
        // loudly beats inventing an empty one and scoping data to it (R18).
        val subject = requireNotNull(jwt.subject) { "verified token carries no subject claim" }
        return Actor(
            subject = subject,
            contact = CustomerContact(
                displayName = jwt.getClaimAsString("name"),
                email = jwt.getClaimAsString("email"),
                phone = jwt.getClaimAsString("phone_number"),
            ),
        )
    }
}
