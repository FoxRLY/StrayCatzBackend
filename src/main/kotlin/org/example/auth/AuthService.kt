package org.example.auth

import io.quarkus.logging.Log
import io.smallrye.jwt.auth.principal.DefaultJWTParser
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.*

/**
 * Проверка токена — одна на сокет и REST.
 *
 * WebSocket (браузер не умеет ставить заголовки на WS):
 *   Sec-WebSocket-Protocol: straycatz.v1, bearer.<access_token>
 *   Sec-WebSocket-Protocol: straycatz.v1, dev.<uuid>          (только allow-dev-tokens=true)
 *
 * REST:
 *   Authorization: Bearer <access_token>
 *   Authorization: Dev <uuid>                                (только allow-dev-tokens=true)
 *
 * JWT проверяется по JWKS Keycloak'а (подпись, iss, exp). sub = id в Keycloak =
 * users.id. Нет строки в users — создаём (just-in-time), username из
 * preferred_username. Парсер собираем сами: CDI-бин JWTParser есть только
 * с расширением quarkus-smallrye-jwt.
 */
@ApplicationScoped
class AuthService(
    private val users: UserProvisioning,
    @ConfigProperty(name = "straycatz.keycloak.url")
    keycloakUrl: String,
    @ConfigProperty(name = "straycatz.keycloak.realm")
    realm: String,
    @ConfigProperty(name = "straycatz.auth.allow-dev-tokens", defaultValue = "false")
    private val allowDevTokens: Boolean,
    @ConfigProperty(name = "straycatz.auth.username-claim", defaultValue = "preferred_username")
    private val usernameClaim: String,
) {
    private val jwtParser = DefaultJWTParser(
        JWTAuthContextInfo().apply {
            val realmUrl = "${keycloakUrl.trimEnd('/')}/realms/$realm"
            // JWKS грузится лениво при первом bearer-токене и кэшируется
            publicKeyLocation = "$realmUrl/protocol/openid-connect/certs"
            issuedBy = realmUrl
        },
    )

    /** Для WebSocket: значение заголовка Sec-WebSocket-Protocol. */
    fun resolve(subProtocolHeader: String?): AuthResult {
        if (subProtocolHeader.isNullOrBlank()) return AuthResult.Missing
        val parts = subProtocolHeader.split(",").map { it.trim() }

        parts.firstOrNull { it.startsWith("bearer.") }?.let { return fromJwt(it.removePrefix("bearer.")) }
        if (allowDevTokens) {
            parts.firstOrNull { it.startsWith("dev.") }?.let { return fromDevToken(it.removePrefix("dev.")) }
        }
        return AuthResult.Missing
    }

    /** Для REST: значение заголовка Authorization. */
    fun resolveAuthorization(header: String?): AuthResult {
        if (header.isNullOrBlank()) return AuthResult.Missing
        val (scheme, value) = header.trim().split(Regex("\\s+"), limit = 2).let {
            if (it.size == 2) it[0] to it[1] else return AuthResult.Invalid
        }
        return when {
            scheme.equals("Bearer", ignoreCase = true) -> fromJwt(value)
            scheme.equals("Dev", ignoreCase = true) && allowDevTokens -> fromDevToken(value)
            else -> AuthResult.Invalid
        }
    }

    /** Access token, только что выданный Keycloak'ом (логин/регистрация). */
    fun resolveAccessToken(token: String): AuthResult = fromJwt(token)

    private fun fromJwt(token: String): AuthResult {
        val jwt = try {
            jwtParser.parse(token)
        } catch (e: Exception) {
            Log.debugf("JWT отклонён: %s", e.message)
            return AuthResult.Invalid
        }
        val userId = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return AuthResult.Invalid
        val username = claimString(jwt.getClaim<Any>(usernameClaim)) ?: return AuthResult.Invalid
        val email = claimString(jwt.getClaim<Any>("email"))
        return when (val r = users.ensure(userId, username)) {
            is AuthResult.Ok -> AuthResult.Ok(r.ticket.copy(email = email))
            else -> r
        }
    }

    private fun claimString(v: Any?): String? = v?.toString()?.trim('"')?.takeIf { it.isNotBlank() }

    private fun fromDevToken(value: String): AuthResult {
        val userId = runCatching { UUID.fromString(value.trim()) }.getOrNull() ?: return AuthResult.Invalid
        return users.existing(userId)
    }
}

