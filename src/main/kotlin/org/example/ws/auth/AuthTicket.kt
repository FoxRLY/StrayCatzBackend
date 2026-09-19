package org.example.ws.auth

import io.smallrye.jwt.auth.principal.JWTParser
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

data class AuthTicket(val userId: UUID, val handle: String)

sealed class AuthResult {
    data class Ok(val ticket: AuthTicket) : AuthResult()
    data object Missing : AuthResult()
    data object Invalid : AuthResult()
}

/**
 * Токен приходит не в query (адреса сокетов целиком попадают в логи прокси),
 * а подпротоколом: `Sec-WebSocket-Protocol: straycatz.v1, bearer.<JWT>`.
 *
 * Аутентификацию делаем не в HttpUpgradeCheck (там пришлось бы отбивать
 * апгрейд HTTP-статусом, а протокол хочет закрытие сокета кодом 4401 "до
 * первого кадра"), а прямо в @OnOpen: коннекшн уже установлен, но клиент
 * ещё не прислал ни одного кадра — можно тихо connection.close() с нужным
 * кодом.
 */
@ApplicationScoped
class AuthService(private val jwtParser: JWTParser) {

    fun resolve(subProtocolHeader: String?): AuthResult {
        if (subProtocolHeader.isNullOrBlank()) return AuthResult.Missing

        val token = subProtocolHeader
            .split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("bearer.") }
            ?.removePrefix("bearer.")
            ?: return AuthResult.Missing

        return try {
            val jwt = jwtParser.parse(token)
            val sub = jwt.subject ?: return AuthResult.Invalid
            val handle = jwt.getClaim<String>("handle") ?: return AuthResult.Invalid
            AuthResult.Ok(AuthTicket(UUID.fromString(sub), handle))
        } catch (e: Exception) {
            AuthResult.Invalid
        }
    }
}
