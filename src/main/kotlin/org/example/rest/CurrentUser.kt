package org.example.rest

import jakarta.enterprise.context.ApplicationScoped
import org.example.auth.AuthResult
import org.example.auth.AuthService
import org.example.auth.AuthTicket

/**
 * Кто делает REST-запрос. Вызывается явно в начале защищённых методов:
 *
 *   fun me(@HeaderParam("Authorization") authorization: String?) = profiles.account(currentUser.require(authorization))
 *
 * Специально без ContainerRequestFilter: проверка JWT при первом запросе
 * ходит за JWKS по HTTP, а JIT-провижининг — в БД; в методе ресурса мы
 * гарантированно на worker-потоке, в фильтре — не всегда.
 */
@ApplicationScoped
class CurrentUser(private val auth: AuthService) {

    fun require(authorization: String?): AuthTicket = when (val r = auth.resolveAuthorization(authorization)) {
        is AuthResult.Ok -> r.ticket
        AuthResult.Blocked -> throw ApiException.forbidden("аккаунт удалён или заблокирован")
        AuthResult.Missing -> throw ApiException.unauthorized("нет заголовка Authorization: Bearer <token>")
        AuthResult.Invalid -> throw ApiException.unauthorized("токен невалиден или истёк")
    }
}
