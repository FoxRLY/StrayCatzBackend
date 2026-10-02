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
import org.example.service.RoomService
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Комнаты. Чужая комната — по username, своя — через /me.
 * (username "me" зарезервирован при регистрации.)
 */
@Path("/api/rooms")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class RoomResource(
    private val currentUser: CurrentUser,
    private val rooms: RoomService,
) {
    // ------------------------------------------------------------ смотреть

    /** Комната целиком: хозяин, вид, ссылки, друзья, сообщества, гостевая. /api/rooms/me — своя. */
    @GET
    @Path("/{username}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("username") username: String): RoomOut {
        val me = currentUser.require(auth)
        return rooms.view(me.userId, resolve(username, me.username))
    }

    /** Отметить заход (гость за сутки). Звать один раз при открытии комнаты. */
    @POST
    @Path("/{username}/visit")
    fun visit(@HeaderParam("Authorization") auth: String?, @PathParam("username") username: String): VisitOut =
        rooms.visit(currentUser.require(auth).userId, username)

    /** «Позвать в гости»: хозяину {username} придёт приглашение в МОЮ комнату. */
    @POST
    @Path("/{username}/invite")
    fun invite(@HeaderParam("Authorization") auth: String?, @PathParam("username") username: String): InviteOut =
        rooms.invite(currentUser.require(auth).userId, username)

    /** Все друзья хозяина (блок «друзья» показывает первые 12). */
    @GET
    @Path("/{username}/friends")
    fun friends(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("username") username: String,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): FriendsPageOut {
        val me = currentUser.require(auth)
        return rooms.friendsOf(me.userId, resolve(username, me.username), limit, offset)
    }

    /** Гостевая, свежие сверху. Дальше — ?before=<createdAt последней записи>. */
    @GET
    @Path("/{username}/guestbook")
    fun guestbook(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("username") username: String,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): GuestbookPageOut {
        val me = currentUser.require(auth)
        return rooms.guestbook(me.userId, resolve(username, me.username), parseInstant(before), limit)
    }

    /** {body} — «Подписать». */
    @POST
    @Path("/{username}/guestbook")
    fun sign(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("username") username: String,
        req: GuestbookIn?,
    ): Response {
        val me = currentUser.require(auth)
        val entry = rooms.sign(me.userId, resolve(username, me.username), req?.body, req?.mediaIds ?: emptyList(), req?.trackIds ?: emptyList())
        return Response.status(201).entity(entry).build()
    }

    // ------------------------------------------------------------ своя комната

    /** Своя комната. Отдельный метод, а не /{username}: иначе JAX-RS выберет шаблон /me и ответит 405 на GET. */
    @GET
    @Path("/me")
    fun mine(@HeaderParam("Authorization") auth: String?): RoomOut {
        val me = currentUser.require(auth)
        return rooms.view(me.userId, me.username)
    }

    /** {title?, mood?, about?, sticker?, theme?, wallpaper?, wallFit?, wallVeil?, wallBlur?, tilt?, dialect?} */
    @PATCH
    @Path("/me")
    fun update(@HeaderParam("Authorization") auth: String?, patch: RoomPatchIn?): RoomOut =
        rooms.update(currentUser.require(auth).userId, patch ?: RoomPatchIn())

    /** {mediaId} (после POST /api/media) или {url}. */
    @PUT
    @Path("/me/wall-image")
    fun setWallImage(@HeaderParam("Authorization") auth: String?, req: WallImageIn?): RoomOut =
        rooms.setWallImage(currentUser.require(auth).userId, req ?: WallImageIn())

    @DELETE
    @Path("/me/wall-image")
    fun clearWallImage(@HeaderParam("Authorization") auth: String?): RoomOut =
        rooms.clearWallImage(currentUser.require(auth).userId)

    /** {blocks: ["about","friends",...]} — какие блоки видны и в каком порядке. */
    @PUT
    @Path("/me/blocks")
    fun setBlocks(@HeaderParam("Authorization") auth: String?, req: BlocksIn?): RoomOut =
        rooms.setBlocks(currentUser.require(auth).userId, req ?: BlocksIn())

    /** {words: {"guestbook_sign": "Черкнуть", ...}} — полностью заменяет словарь. */
    @PUT
    @Path("/me/words")
    fun setWords(@HeaderParam("Authorization") auth: String?, req: WordsIn?): RoomOut =
        rooms.setWords(currentUser.require(auth).userId, req ?: WordsIn())

    /** {title, url} -> 201 созданная ссылка. */
    @POST
    @Path("/me/links")
    fun addLink(@HeaderParam("Authorization") auth: String?, req: LinkIn?): Response =
        Response.status(201).entity(rooms.addLink(currentUser.require(auth).userId, req ?: LinkIn())).build()

    @PATCH
    @Path("/me/links/{id}")
    fun updateLink(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: LinkIn?): RoomLinkOut =
        rooms.updateLink(currentUser.require(auth).userId, id, req ?: LinkIn())

    @DELETE
    @Path("/me/links/{id}")
    fun deleteLink(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        rooms.deleteLink(currentUser.require(auth).userId, id)
        return Response.noContent().build()
    }

    /** {ids: [...]} — новый порядок всех ссылок. */
    @PUT
    @Path("/me/links/order")
    fun reorderLinks(@HeaderParam("Authorization") auth: String?, req: LinkOrderIn?): List<RoomLinkOut> =
        rooms.reorderLinks(currentUser.require(auth).userId, req?.ids)

    /** Кто заходил за сутки. */
    @GET
    @Path("/me/guests")
    fun guests(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): List<GuestOut> = rooms.guests(currentUser.require(auth).userId, limit)

    // ------------------------------------------------------------

    private fun resolve(username: String, myUsername: String) =
        if (username.equals("me", ignoreCase = true)) myUsername else username

    private fun parseInstant(v: String?): Instant? = v?.takeIf { it.isNotBlank() }?.let {
        try {
            Instant.parse(it)
        } catch (e: DateTimeParseException) {
            throw ApiException.badRequest("invalid_before", "before — ISO-время, например 2026-09-27T12:00:00Z")
        }
    }
}

/** Удаление записи гостевой — автором или хозяином комнаты. */
@Path("/api/guestbook")
@Produces(MediaType.APPLICATION_JSON)
class GuestbookResource(
    private val currentUser: CurrentUser,
    private val rooms: RoomService,
) {
    @DELETE
    @Path("/{entryId}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("entryId") entryId: UUID): Response {
        rooms.deleteEntry(currentUser.require(auth).userId, entryId)
        return Response.noContent().build()
    }
}
