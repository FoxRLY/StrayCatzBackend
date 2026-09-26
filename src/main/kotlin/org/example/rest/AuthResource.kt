package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.AccountService

/**
 * Публичные ручки авторизации. Фронт хранит accessToken (в памяти) и
 * refreshToken, access шлёт в `Authorization: Bearer` и в сабпротокол сокета
 * (`bearer.<token>`), а когда access истекает — меняет пару через /refresh.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AuthResource(private val accounts: AccountService) {

    /** {username, email, password} -> 201 {user, tokens} — сразу залогинен. */
    @POST
    @Path("/register")
    fun register(req: RegisterIn?): Response =
        Response.status(201).entity(accounts.register(req ?: RegisterIn())).build()

    /** {login (username или email), password} -> {user, tokens} */
    @POST
    @Path("/login")
    fun login(req: LoginIn?): AuthOut = accounts.login(req ?: LoginIn())

    /** {refreshToken} -> tokens */
    @POST
    @Path("/refresh")
    fun refresh(req: RefreshIn?): TokensOut = accounts.refresh(req ?: RefreshIn())

    /** {refreshToken} -> 204, сессия в Keycloak закрыта */
    @POST
    @Path("/logout")
    fun logout(req: RefreshIn?): Response {
        accounts.logout(req ?: RefreshIn())
        return Response.noContent().build()
    }
}
