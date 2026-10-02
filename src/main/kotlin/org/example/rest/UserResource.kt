package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.service.AccountService
import org.example.service.CommunityService
import org.example.service.MediaService
import org.example.service.RoomService
import org.example.service.UserProfileService
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.util.UUID

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class UserResource(
    private val currentUser: CurrentUser,
    private val profiles: UserProfileService,
    private val accounts: AccountService,
    private val communities: CommunityService,
    private val media: MediaService,
    private val rooms: RoomService,
    @ConfigProperty(name = "straycatz.media.max-avatar-bytes", defaultValue = "5242880")
    private val maxAvatarBytes: Long,
) {

    /** Свой профиль (+ email). */
    @GET
    @Path("/me")
    fun me(@HeaderParam("Authorization") authorization: String?): AccountOut =
        profiles.account(currentUser.require(authorization))

    /**
     * {avatar?, avatarMediaId?, color?, tagline?, mood?, roomTitle?}: null/нет поля — не трогаем, "" — очистить.
     * mood и roomTitle — это настроение и название своей комнаты (то же, что PATCH /api/rooms/me).
     */
    @PATCH
    @Path("/me")
    fun updateMe(@HeaderParam("Authorization") authorization: String?, patch: UpdateProfileIn?): AccountOut {
        val ticket = currentUser.require(authorization)
        val p = patch ?: UpdateProfileIn()
        if (p.mood != null || p.roomTitle != null) {
            rooms.update(ticket.userId, RoomPatchIn(title = p.roomTitle, mood = p.mood))
        }
        return profiles.update(ticket, p)
    }

    /**
     * Загрузить аватар одним запросом: multipart/form-data, поле file (png/jpeg/gif/webp, до 5 МБ).
     * Ответ — свой профиль с новым avatar ("/api/media/{id}").
     */
    @PUT
    @Path("/me/avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadAvatar(@HeaderParam("Authorization") authorization: String?, @RestForm("file") file: FileUpload?): AccountOut {
        val me = currentUser.require(authorization)
        val f = file ?: throw ApiException.badRequest("no_file", "нужно поле file (multipart/form-data)")
        val uploaded = media.upload(me.userId, f.uploadedFile(), f.size(), onlyImages = true, maxOverride = maxAvatarBytes)
        return profiles.setAvatar(me, uploaded.id)
    }

    /** То же, но картинка уже загружена через POST /api/media: {mediaId}. */
    @PUT
    @Path("/me/avatar")
    @Consumes(MediaType.APPLICATION_JSON)
    fun setAvatar(@HeaderParam("Authorization") authorization: String?, req: AvatarIn?): AccountOut =
        profiles.setAvatar(
            currentUser.require(authorization),
            req?.mediaId ?: throw ApiException.badRequest("invalid_media", "нужен mediaId"),
        )

    /** Убрать аватар. */
    @DELETE
    @Path("/me/avatar")
    fun deleteAvatar(@HeaderParam("Authorization") authorization: String?): AccountOut =
        profiles.clearAvatar(currentUser.require(authorization))

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

    /** Профиль по логину — для ссылок вида /rooms/{username}. */
    @GET
    @Path("/by-username/{username}")
    fun byUsername(@HeaderParam("Authorization") authorization: String?, @PathParam("username") username: String): UserProfileOut {
        currentUser.require(authorization)
        return profiles.profileByUsername(username)
    }

    /** Сообщества пользователя. */
    @GET
    @Path("/{id}/communities")
    fun communitiesOf(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: UUID,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): CommunitiesPageOut {
        currentUser.require(authorization)
        return communities.ofUser(id, limit)
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
