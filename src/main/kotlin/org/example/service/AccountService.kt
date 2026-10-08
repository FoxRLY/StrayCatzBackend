package org.example.service

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.example.auth.AuthResult
import org.example.auth.AuthService
import org.example.auth.AuthTicket
import org.example.auth.KeycloakClient
import org.example.auth.UserProvisioning
import org.example.rest.ApiException
import org.example.rest.AuthOut
import org.example.rest.ChangePasswordIn
import org.example.rest.LoginIn
import org.example.rest.RefreshIn
import org.example.rest.RegisterIn
import org.example.rest.TokensOut

/**
 * Регистрация / вход / выход.
 *
 * Связка Keycloak <-> users: id одинаковый. При регистрации сначала создаём
 * пользователя в Keycloak (он проверяет уникальность username/email и
 * политику паролей), берём его id и с ним же вставляем строку в users. Если
 * вставка в нашу БД упала — удаляем только что созданного в Keycloak, чтобы
 * не осталось "полупользователя".
 *
 * Пароли через наш бэкенд только проходят транзитом в Keycloak и нигде не
 * хранятся и не логируются.
 */
@ApplicationScoped
class AccountService(
    private val kc: KeycloakClient,
    private val auth: AuthService,
    private val provisioning: UserProvisioning,
    private val profiles: UserProfileService,
) {
    companion object {
        private val USERNAME_RE = Regex("^[a-z0-9][a-z0-9_.-]{2,31}$")
        /** Занятые под пути фронта/API: /rooms/me, /api/rooms/me и т.п. */
        private val RESERVED_USERNAMES = setOf(
            "me", "admin", "api", "root", "system", "support", "straycatz", "settings", "new", "null", "undefined",
        )
        private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 128
    }

    fun register(req: RegisterIn): AuthOut {
        // Keycloak сам приводит username к нижнему регистру — делаем это заранее,
        // чтобы users.username и preferred_username в токене всегда совпадали.
        val username = req.username?.trim()?.lowercase()
            ?.takeIf { USERNAME_RE.matches(it) }
            ?: throw ApiException.badRequest(
                "invalid_username",
                "логин: 3–32 символа, латиница/цифры/._-, начинается с буквы или цифры",
            )
        if (username in RESERVED_USERNAMES) throw ApiException.conflict("username_taken", "этот логин зарезервирован")
        val email = req.email?.trim()?.lowercase()?.takeIf { it.length <= 254 && EMAIL_RE.matches(it) }
            ?: throw ApiException.badRequest("invalid_email", "некорректный email")
        val password = validPassword(req.password)

        if (provisioning.usernameTaken(username)) {
            throw ApiException.conflict("username_taken", "этот логин уже занят")
        }

        val id = kc.createUser(username, email, password)
        try {
            provisioning.createLocal(id, username)
        } catch (e: Exception) {
            Log.errorf(e, "регистрация %s: не смогли записать в users, откатываем Keycloak", username)
            kc.deleteUser(id)
            throw ApiException.conflict("register_failed", "не удалось завершить регистрацию, попробуйте ещё раз")
        }
        Log.infof("зарегистрирован %s (%s)", username, id)
        return login(LoginIn(username, password))
    }

    fun login(req: LoginIn): AuthOut {
        val login = req.login?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException.badRequest("invalid_login", "укажите логин или email")
        val password = req.password?.takeIf { it.isNotEmpty() }
            ?: throw ApiException.badRequest("invalid_password", "укажите пароль")

        val tokens = kc.passwordGrant(login, password)
        val ticket = ticketFrom(tokens.accessToken)
        return AuthOut(profiles.account(ticket), TokensOut.of(tokens))
    }

    fun refresh(req: RefreshIn): TokensOut {
        val rt = req.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw ApiException.badRequest("invalid_refresh_token", "нужен refreshToken")
        val tokens = kc.refresh(rt)
        ticketFrom(tokens.accessToken) // заблокированный/удалённый не получит новый токен
        return TokensOut.of(tokens)
    }

    fun logout(req: RefreshIn) {
        req.refreshToken?.takeIf { it.isNotBlank() }?.let { kc.logout(it) }
    }

    /** Проверяем старый пароль, ставим новый, рвём все сессии, выдаём свежие токены. */
    fun changePassword(ticket: AuthTicket, req: ChangePasswordIn): AuthOut {
        val current = req.currentPassword ?: throw ApiException.badRequest("invalid_password", "укажите текущий пароль")
        val next = validPassword(req.newPassword)
        try {
            kc.passwordGrant(ticket.username, current)
        } catch (e: ApiException) {
            if (e.code == "invalid_credentials") throw ApiException.badRequest("wrong_password", "текущий пароль неверен")
            throw e
        }
        kc.resetPassword(ticket.userId, next)
        kc.logoutAllSessions(ticket.userId)
        return login(LoginIn(ticket.username, next))
    }

    /** Мягко удаляем у нас, блокируем в Keycloak, рвём сессии. */
    fun deleteAccount(ticket: AuthTicket) {
        kc.setEnabled(ticket.userId, false)
        kc.logoutAllSessions(ticket.userId)
        profiles.softDelete(ticket.userId)
        Log.infof("аккаунт %s (%s) удалён", ticket.username, ticket.userId)
    }

    private fun validPassword(p: String?): String {
        if (p == null || p.length < MIN_PASSWORD || p.length > MAX_PASSWORD) {
            throw ApiException.badRequest("weak_password", "пароль: от $MIN_PASSWORD до $MAX_PASSWORD символов")
        }
        return p
    }

    private fun ticketFrom(accessToken: String): AuthTicket = when (val r = auth.resolveAccessToken(accessToken)) {
        is AuthResult.Ok -> r.ticket
        AuthResult.Blocked -> throw ApiException.forbidden("аккаунт удалён или заблокирован")
        is AuthResult.Banned -> throw ApiException(
            403, "account_banned", "аккаунт заблокирован" + (r.reason?.let { ": $it" } ?: ""),
            mapOf("until" to r.until.takeIf { it < org.example.auth.Staff.FOREVER }, "forever" to (r.until >= org.example.auth.Staff.FOREVER), "reason" to r.reason),
        )
        else -> {
            // Keycloak выдал токен, а мы его не приняли — почти всегда это
            // расхождение issuer (straycatz.keycloak.url vs KC_HOSTNAME)
            Log.error("токен от Keycloak не прошёл проверку — сверь straycatz.keycloak.url и KC_HOSTNAME")
            throw ApiException(502, "auth_misconfigured", "токен от Keycloak не прошёл проверку")
        }
    }
}
