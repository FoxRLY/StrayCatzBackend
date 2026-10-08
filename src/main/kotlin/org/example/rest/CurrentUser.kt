package org.example.rest

import jakarta.enterprise.context.ApplicationScoped
import org.example.auth.AuthResult
import org.example.auth.AuthService
import org.example.auth.AuthTicket
import org.example.auth.Staff
import org.example.service.PresenceService
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager
import java.time.Instant

/**
 * Кто делает REST-запрос. Вызывается явно в начале защищённых методов:
 *
 *   fun me(@HeaderParam("Authorization") authorization: String?) = profiles.account(currentUser.require(authorization))
 *
 * Специально без ContainerRequestFilter: проверка JWT при первом запросе
 * ходит за JWKS по HTTP, а JIT-провижининг — в БД; в методе ресурса мы
 * гарантированно на worker-потоке, в фильтре — не всегда.
 *
 * Модерация (V20):
 *  - заблокированный (бан) получает 403 `account_banned` на любой запрос, details: {until, reason, forever};
 *  - ограниченный — 403 `account_restricted` на любой изменяющий запрос, кроме «пассивных»
 *    (прочтения, просмотры, уведомления, жалобы, выход) — см. [RESTRICTED_ALLOWED].
 */
@ApplicationScoped
class CurrentUser(
    private val auth: AuthService,
    private val presence: PresenceService,
) {
    companion object {
        /** Что можно ограниченному из изменяющих запросов. */
        private val RESTRICTED_ALLOWED = listOf(
            Regex("^/api/auth/.*"),
            Regex("^/api/reports/?$"),
            Regex("^/api/notifications(/.*)?$"),
            Regex("^/api/posts/[^/]+/read$"),
            Regex("^/api/video/[^/]+/view$"),
            Regex("^/api/radio/[^/]+/listen$"),
            Regex("^/api/chats/[^/]+/read$"),
            Regex("^/api/presence(/.*)?$"),
        )
    }

    fun require(authorization: String?): AuthTicket = when (val r = auth.resolveAuthorization(authorization)) {
        is AuthResult.Ok -> r.ticket.also { touch(it) }
        is AuthResult.Banned -> throw banned(r.until, r.reason)
        AuthResult.Blocked -> throw ApiException.forbidden("аккаунт удалён или заблокирован")
        AuthResult.Missing -> throw ApiException.unauthorized("нет заголовка Authorization: Bearer <token>")
        AuthResult.Invalid -> throw ApiException.unauthorized("токен невалиден или истёк")
    }

    /** То же + нужна роль модератора (или диктатора). */
    fun requireModerator(authorization: String?): AuthTicket = require(authorization).also {
        if (!it.isModerator) throw ApiException(403, "not_moderator", "нужна роль модератора")
    }

    /** То же + нужна роль верховного диктатора. */
    fun requireDictator(authorization: String?): AuthTicket = require(authorization).also {
        if (!it.isDictator) throw ApiException(403, "not_dictator", "это может только верховный диктатор")
    }

    /**
     * Изменяющий запрос (POST/PUT/PATCH/DELETE) — человек что-то делает: для
     * presence это действие (не «отошёл»). GET не считаем: фронт сам опрашивает
     * счётчики и прогресс, и это держало бы в сети открытую вкладка.
     * Здесь же — ограничение «только читать».
     */
    private fun touch(t: AuthTicket) {
        // текущий запрос Quarkus REST (есть, пока мы внутри метода ресурса)
        val req = runCatching { CurrentRequestManager.get() }.getOrNull() ?: return
        val method = runCatching { req.method }.getOrNull() ?: return
        if (method == "GET" || method == "HEAD" || method == "OPTIONS") return
        if (t.isRestricted) {
            val path = runCatching { req.path }.getOrNull().orEmpty().substringBefore('?')
            if (RESTRICTED_ALLOWED.none { it.matches(path) }) {
                val until = t.restrictedUntil!!
                throw ApiException(
                    403, "account_restricted",
                    "аккаунт ограничен модератором" + (if (until >= Staff.FOREVER) " навсегда" else " до $until") +
                        (t.restrictReason?.let { ": $it" } ?: "") + " — можно только читать",
                    mapOf("until" to until.takeIf { it < Staff.FOREVER }, "forever" to (until >= Staff.FOREVER), "reason" to t.restrictReason),
                )
            }
        }
        runCatching { presence.touch(t.userId) }
    }

    private fun banned(until: Instant, reason: String?) = ApiException(
        403, "account_banned",
        "аккаунт заблокирован" + (if (until >= Staff.FOREVER) " навсегда" else " до $until") + (reason?.let { ": $it" } ?: ""),
        mapOf("until" to until.takeIf { it < Staff.FOREVER }, "forever" to (until >= Staff.FOREVER), "reason" to reason),
    )
}
