package org.example.auth

import java.time.Instant
import java.util.UUID

/** Кто пришёл. email есть только у JWT из Keycloak (у dev-токена null). */
data class AuthTicket(
    val userId: UUID,
    val username: String,
    val email: String? = null,
    /** Роли платформы из токена: moderator, dictator (диктатор — всегда и модератор). */
    val roles: Set<String> = emptySet(),
    /** Ограничен модератором до этого момента: можно только читать. */
    val restrictedUntil: Instant? = null,
    val restrictReason: String? = null,
) {
    val isModerator: Boolean get() = Staff.MODERATOR in roles || Staff.DICTATOR in roles
    val isDictator: Boolean get() = Staff.DICTATOR in roles
    val isRestricted: Boolean get() = restrictedUntil?.isAfter(Instant.now()) == true
    /** Для users.staff_role: старшая роль. */
    val staffRole: String? get() = when {
        isDictator -> Staff.DICTATOR
        isModerator -> Staff.MODERATOR
        else -> null
    }
}

object Staff {
    const val MODERATOR = "moderator"
    const val DICTATOR = "dictator"
    /** «Навсегда» для банов и ограничений (не 'infinity': его плохо понимают драйверы). */
    val FOREVER: Instant = Instant.parse("9999-12-31T00:00:00Z")
}
