package org.example.rest

import jakarta.enterprise.context.ApplicationScoped
import org.example.auth.AuthResult
import org.example.auth.AuthService
import org.example.auth.AuthTicket
import org.example.service.PresenceService
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager

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
class CurrentUser(
    private val auth: AuthService,
    private val presence: PresenceService,
) {

    fun require(authorization: String?): AuthTicket = when (val r = auth.resolveAuthorization(authorization)) {
        is AuthResult.Ok -> r.ticket.also { touch(it.userId) }
        AuthResult.Blocked -> throw ApiException.forbidden("аккаунт удалён или заблокирован")
        AuthResult.Missing -> throw ApiException.unauthorized("нет заголовка Authorization: Bearer <token>")
        AuthResult.Invalid -> throw ApiException.unauthorized("токен невалиден или истёк")
    }

    /**
     * Изменяющий запрос (POST/PUT/PATCH/DELETE) — человек что-то делает: для
     * presence это действие (не «отошёл»). GET не считаем: фронт сам опрашивает
     * счётчики и прогресс, и это держало бы в сети открытую вкладку.
     */
    private fun touch(userId: java.util.UUID) {
        // текущий запрос Quarkus REST (есть, пока мы внутри метода ресурса)
        val method = runCatching { CurrentRequestManager.get()?.method }.getOrNull() ?: return
        if (method == "GET" || method == "HEAD" || method == "OPTIONS") return
        runCatching { presence.touch(userId) }
    }
}
