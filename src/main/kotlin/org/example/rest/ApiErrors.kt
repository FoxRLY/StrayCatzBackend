package org.example.rest

import io.quarkus.logging.Log
import jakarta.persistence.PersistenceException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/** Единый формат ошибок REST: `{ "code": "...", "message": "..." }` + HTTP-статус. */
data class ApiError(val code: String, val message: String)

class ApiException(val status: Int, val code: String, message: String) : RuntimeException(message) {
    companion object {
        fun badRequest(code: String, msg: String) = ApiException(400, code, msg)
        fun unauthorized(msg: String = "нужен валидный токен") = ApiException(401, "unauthorized", msg)
        fun forbidden(msg: String) = ApiException(403, "forbidden", msg)
        fun notFound(msg: String) = ApiException(404, "not_found", msg)
        fun conflict(code: String, msg: String) = ApiException(409, code, msg)
    }
}

private fun json(status: Int, code: String, message: String): Response =
    Response.status(status).type(MediaType.APPLICATION_JSON).entity(ApiError(code, message)).build()

@Provider
class ApiExceptionMapper : ExceptionMapper<ApiException> {
    override fun toResponse(e: ApiException): Response = json(e.status, e.code, e.message ?: e.code)
}

/** Гонка на unique-индексе (два одновременных запроса) — отдаём 409, а не 500. */
@Provider
class PersistenceExceptionMapper : ExceptionMapper<PersistenceException> {
    override fun toResponse(e: PersistenceException): Response {
        Log.debug("PersistenceException", e)
        return json(409, "conflict", "конфликт при записи, повторите запрос")
    }
}

/** Всё остальное — 500 с логом, без стектрейса наружу. JAX-RS-исключения не трогаем. */
@Provider
class FallbackExceptionMapper : ExceptionMapper<Exception> {
    override fun toResponse(e: Exception): Response {
        if (e is WebApplicationException) return e.response
        Log.error("необработанная ошибка REST", e)
        return json(500, "internal", "внутренняя ошибка")
    }
}
