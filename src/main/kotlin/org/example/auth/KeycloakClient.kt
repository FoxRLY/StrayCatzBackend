package org.example.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.rest.ApiException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Тонкий клиент к Keycloak на JDK HttpClient — без keycloak-admin-client,
 * который тянет свой RESTEasy и любит конфликтовать с Quarkus.
 *
 * Два "клиента" в realm'е:
 *  - straycatz-web (public) — от его имени логиним пользователя (password grant),
 *    токены получаются такие же, как если бы фронт логинился сам;
 *  - straycatz-backend (confidential, service account с ролями
 *    realm-management: manage-users/view-users) — создаёт/блокирует
 *    пользователей через Admin REST API.
 *
 * Все вызовы блокирующие — вызывать только с worker-потока (REST-методы
 * Quarkus без Uni/suspend именно там и выполняются).
 */
@ApplicationScoped
class KeycloakClient(
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "straycatz.keycloak.url") keycloakUrl: String,
    @ConfigProperty(name = "straycatz.keycloak.realm") realm: String,
    @ConfigProperty(name = "straycatz.keycloak.public-client-id", defaultValue = "straycatz-web")
    private val publicClientId: String,
    @ConfigProperty(name = "straycatz.keycloak.admin-client-id", defaultValue = "straycatz-backend")
    private val adminClientId: String,
    @ConfigProperty(name = "straycatz.keycloak.admin-client-secret")
    private val adminClientSecret: String,
) {
    private val base = keycloakUrl.trimEnd('/')
    private val tokenUrl = "$base/realms/$realm/protocol/openid-connect/token"
    private val logoutUrl = "$base/realms/$realm/protocol/openid-connect/logout"
    private val adminUrl = "$base/admin/realms/$realm"

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Volatile private var adminToken: String? = null
    @Volatile private var adminTokenExpiresAt: Instant = Instant.EPOCH

    // ------------------------------------------------------------ user tokens

    /** login = username ИЛИ email (в realm'е включён loginWithEmailAllowed). */
    fun passwordGrant(login: String, password: String): KcTokenResponse {
        val r = postForm(
            tokenUrl,
            mapOf(
                "grant_type" to "password",
                "client_id" to publicClientId,
                "username" to login,
                "password" to password,
                "scope" to "openid profile email",
            ),
        )
        if (r.statusCode() == 200) return mapper.readValue(r.body(), KcTokenResponse::class.java)
        val desc = errorDescription(r.body())
        Log.debugf("password grant отклонён: %d %s", r.statusCode(), desc)
        throw when {
            desc.contains("disabled", ignoreCase = true) -> ApiException.forbidden("аккаунт заблокирован")
            desc.contains("not fully set up", ignoreCase = true) ->
                ApiException(403, "account_not_ready", "аккаунт не донастроен в Keycloak: $desc")
            r.statusCode() in 400..401 -> ApiException(401, "invalid_credentials", "неверный логин или пароль")
            else -> upstream(r)
        }
    }

    fun refresh(refreshToken: String): KcTokenResponse {
        val r = postForm(
            tokenUrl,
            mapOf("grant_type" to "refresh_token", "client_id" to publicClientId, "refresh_token" to refreshToken),
        )
        if (r.statusCode() == 200) return mapper.readValue(r.body(), KcTokenResponse::class.java)
        if (r.statusCode() in 400..401) throw ApiException(401, "invalid_refresh_token", "сессия истекла, войдите заново")
        throw upstream(r)
    }

    /** Завершает SSO-сессию в Keycloak: refresh token больше не работает. */
    fun logout(refreshToken: String) {
        val r = postForm(logoutUrl, mapOf("client_id" to publicClientId, "refresh_token" to refreshToken))
        if (r.statusCode() !in listOf(200, 204, 400)) throw upstream(r) // 400 = уже невалиден, ок
    }

    // ------------------------------------------------------------ admin API

    /** @return id пользователя в Keycloak (он же будет users.id). */
    fun createUser(username: String, email: String, password: String): UUID {
        val body = mapOf(
            "username" to username,
            "email" to email,
            "enabled" to true,
            "emailVerified" to false,
            // Keycloak 24+ по умолчанию требует firstName/lastName в профиле:
            // без них при логине вылезает required action "обновить профиль" и
            // password grant падает с "Account is not fully set up".
            "firstName" to username,
            "lastName" to username,
            "credentials" to listOf(mapOf("type" to "password", "value" to password, "temporary" to false)),
        )
        val r = admin("POST", "/users", body)
        when (r.statusCode()) {
            201 -> {
                val location = r.headers().firstValue("Location").orElseThrow { upstream(r) }
                return UUID.fromString(location.substringAfterLast('/'))
            }
            409 -> {
                val msg = errorDescription(r.body())
                throw if (msg.contains("email", ignoreCase = true)) {
                    ApiException.conflict("email_taken", "этот email уже зарегистрирован")
                } else {
                    ApiException.conflict("username_taken", "этот логин уже занят")
                }
            }
            400 -> throw ApiException.badRequest("invalid_user", errorDescription(r.body()))
            else -> throw upstream(r)
        }
    }

    fun deleteUser(id: UUID) {
        val r = admin("DELETE", "/users/$id", null)
        if (r.statusCode() !in listOf(204, 404)) Log.warnf("не смогли удалить %s в Keycloak: %d", id, r.statusCode())
    }

    fun setEnabled(id: UUID, enabled: Boolean) {
        // PUT частичным представлением с user profile (KC 24+) может затереть
        // атрибуты или упасть на валидации — берём полное и меняем одно поле.
        val current = admin("GET", "/users/$id", null)
        if (current.statusCode() == 404) return
        if (current.statusCode() != 200) throw upstream(current)
        val rep = mapper.readTree(current.body()) as com.fasterxml.jackson.databind.node.ObjectNode
        rep.put("enabled", enabled)
        val r = admin("PUT", "/users/$id", rep)
        if (r.statusCode() != 204) throw upstream(r)
    }

    /** Разлогинить все сессии пользователя (после смены пароля / удаления). */
    fun logoutAllSessions(id: UUID) {
        val r = admin("POST", "/users/$id/logout", null)
        if (r.statusCode() !in listOf(204, 404)) Log.warnf("logout-all %s: %d", id, r.statusCode())
    }

    fun resetPassword(id: UUID, newPassword: String) {
        val r = admin(
            "PUT",
            "/users/$id/reset-password",
            mapOf("type" to "password", "value" to newPassword, "temporary" to false),
        )
        when (r.statusCode()) {
            204 -> return
            400 -> throw ApiException.badRequest("weak_password", errorDescription(r.body()))
            else -> throw upstream(r)
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun admin(method: String, path: String, body: Any?, retried: Boolean = false): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        val req = HttpRequest.newBuilder(URI.create(adminUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer ${adminAccessToken()}")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        val r = send(req)
        if (r.statusCode() == 401 && !retried) {
            adminToken = null // протух — возьмём новый и повторим один раз
            return admin(method, path, body, retried = true)
        }
        if (r.statusCode() == 403) {
            Log.error("service account $adminClientId не имеет прав realm-management (manage-users)")
        }
        return r
    }

    @Synchronized
    private fun adminAccessToken(): String {
        val cached = adminToken
        if (cached != null && Instant.now().isBefore(adminTokenExpiresAt)) return cached
        val r = postForm(
            tokenUrl,
            mapOf(
                "grant_type" to "client_credentials",
                "client_id" to adminClientId,
                "client_secret" to adminClientSecret,
            ),
        )
        if (r.statusCode() != 200) {
            Log.errorf("не смогли получить сервисный токен %s: %d %s", adminClientId, r.statusCode(), r.body())
            if (r.statusCode() == 401) {
                Log.errorf(
                    "Подсказка: в realm нет клиента %s или у него другой secret. Realm импортируется " +
                        "только при первом старте Keycloak — добавь клиента (см. README, раздел про Keycloak) " +
                        "и сверь straycatz.keycloak.admin-client-secret.",
                    adminClientId,
                )
            }
            throw upstream(r)
        }
        val t = mapper.readValue(r.body(), KcTokenResponse::class.java)
        adminToken = t.accessToken
        adminTokenExpiresAt = Instant.now().plusSeconds((t.expiresIn - 30).coerceAtLeast(5))
        return t.accessToken
    }

    private fun postForm(url: String, params: Map<String, String>): HttpResponse<String> {
        val form = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
        }
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        return send(req)
    }

    private fun send(req: HttpRequest): HttpResponse<String> = try {
        http.send(req, HttpResponse.BodyHandlers.ofString())
    } catch (e: Exception) {
        Log.errorf("Keycloak недоступен (%s): %s", req.uri(), e.message)
        throw ApiException(503, "auth_unavailable", "сервис авторизации недоступен")
    }

    private fun errorDescription(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return runCatching {
            val n = mapper.readTree(body)
            listOf("error_description", "errorMessage", "error").firstNotNullOfOrNull { n.get(it)?.asText() }
        }.getOrNull() ?: body.take(200)
    }

    private fun upstream(r: HttpResponse<String>): ApiException {
        Log.errorf("Keycloak ответил %d на %s: %s", r.statusCode(), r.uri(), r.body()?.take(500))
        return ApiException(502, "auth_upstream_error", "ошибка сервиса авторизации")
    }
}
