package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.AccountService
import org.example.service.UserProfileService
import java.util.UUID

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class UserResource(
    private val currentUser: CurrentUser,
    private val profiles: UserProfileService,
    private val accounts: AccountService,
) {

    /** Свой профиль (+ email). */
    @GET
    @Path("/me")
    fun me(@HeaderParam("Authorization") authorization: String?): AccountOut =
        profiles.account(currentUser.require(authorization))

    /** {avatar?, color?, tagline?}: null/нет поля — не трогаем, "" — очистить. */
    @PATCH
    @Path("/me")
    fun updateMe(@HeaderParam("Authorization") authorization: String?, patch: UpdateProfileIn?): AccountOut =
        profiles.update(currentUser.require(authorization), patch ?: UpdateProfileIn())

    /** {currentPassword, newPassword} -> новые токены (старые сессии разлогинены). */
    @POST
    @Path("/me/password")
    fun changePassword(@HeaderParam("Authorization") authorization: String?, req: ChangePasswordIn?): AuthOut =
        accounts.changePassword(currentUser.require(authorization), req ?: ChangePasswordIn())

    /** Удалить аккаунт: мягкое удаление у нас + блокировка в Keycloak. */
    @DELETE
    @Path("/me")
    fun deleteMe(@HeaderParam("Authorization") authorization: String?): Response {
        accounts.deleteAccount(currentUser.require(authorization))
        return Response.noContent().build()
    }

    /** Публичный профиль по id. */
    @GET
    @Path("/{id}")
    fun byId(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID): UserProfileOut {
        currentUser.require(authorization)
        return profiles.profile(id)
    }

    /** Поиск по началу логина: GET /api/users?q=al&limit=20 (себя не возвращает). */
    @GET
    fun search(
        @HeaderParam("Authorization") authorization: String?,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): List<UserShortOut> {
        val me = currentUser.require(authorization)
        return profiles.search(q.orEmpty(), limit, exclude = me.userId)
    }
}
