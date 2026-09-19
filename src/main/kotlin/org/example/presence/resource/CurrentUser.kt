package com.example.presence.resource

import jakarta.enterprise.context.RequestScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.example.presence.model.AppUser

/**
 * В спецификации не описан механизм аутентификации, поэтому пока
 * личность вызывающего берётся из заголовка X-User-Handle.
 * Когда появится реальная авторизация (OIDC/JWT), этот класс
 * достаточно заменить на извлечение handle из токена.
 */
@RequestScoped
class CurrentUser {

    @Context
    lateinit var headers: HttpHeaders

    fun resolve(): AppUser {
        val handle = headers.getHeaderString("X-User-Handle")
            ?: throw WebApplicationException(
                Response.status(Response.Status.UNAUTHORIZED)
                    .entity(
                        mapOf(
                            "title" to "Unauthorized",
                            "status" to 401,
                            "detail" to "Missing X-User-Handle header"
                        )
                    )
                    .build()
            )
        return AppUser.findByHandle(handle)
            ?: throw WebApplicationException(
                Response.status(Response.Status.UNAUTHORIZED)
                    .entity(mapOf("title" to "Unauthorized", "status" to 401, "detail" to "Unknown user"))
                    .build()
            )
    }

    /** Для публичных ручек (просмотр комнат): null, если гость не залогинен. */
    fun resolveOptional(): AppUser? {
        val handle = headers.getHeaderString("X-User-Handle") ?: return null
        return AppUser.findByHandle(handle)
    }

    private fun unauthorized(detail: String) = WebApplicationException(
        Response.status(Response.Status.UNAUTHORIZED)
            .entity(mapOf("title" to "Unauthorized", "status" to 401, "detail" to detail))
            .build()
    )
}
