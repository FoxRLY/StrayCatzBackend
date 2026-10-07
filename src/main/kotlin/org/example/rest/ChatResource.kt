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
import org.example.service.ChatActionsService
import org.example.service.ChatManagementService
import org.example.service.FriendService
import org.example.service.MediaService
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload
import java.util.UUID

@Path("/api/chats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ChatResource(
    private val currentUser: CurrentUser,
    private val chats: ChatManagementService,
    private val actions: ChatActionsService,
    private val media: MediaService,
    private val calls: org.example.service.CallService,
) {

    /** Мои чаты: последнее сообщение, непрочитанные, собеседник в личке. */
    @GET
    fun list(
        @HeaderParam("Authorization") authorization: String?,
        /** Только чаты этой папки (GET /api/chats/folders). */
        @QueryParam("folder") folder: UUID?,
    ): List<ChatListItemOut> = chats.listMine(currentUser.require(authorization).userId, folder)

    /** {"type":"direct","userId"} | {"type":"group","name","memberIds"} -> 201 (или 200, если личка уже была). */
    @POST
    fun create(@HeaderParam("Authorization") authorization: String?, req: CreateChatIn?): Response {
        val me = currentUser.require(authorization)
        val (details, created) = chats.create(me.userId, req ?: CreateChatIn())
        return Response.status(if (created) 201 else 200).entity(details).build()
    }

    @GET
    @Path("/{id}")
    fun details(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID): ChatDetailsOut =
        chats.details(id, currentUser.require(authorization).userId)

    /**
     * История сообщений:
     *   GET /api/chats/{id}/messages?limit=50              — последние
     *   GET /api/chats/{id}/messages?before=120&limit=50   — старее seq 120
     *   GET /api/chats/{id}/messages?after=97&limit=100    — новее seq 97 (догрузка дыры)
     */
    @GET
    @Path("/{id}/messages")
    fun messages(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: UUID,
        @QueryParam("before") before: Long?,
        @QueryParam("after") after: Long?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): MessagePageOut = chats.history(id, currentUser.require(authorization).userId, before, after, limit)

    /** {userId} — добавить в группу. */
    @POST
    @Path("/{id}/members")
    fun addMember(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: UUID,
        req: AddMemberIn?,
    ): ChatDetailsOut {
        val me = currentUser.require(authorization)
        val userId = req?.userId ?: throw ApiException.badRequest("invalid_member", "нужен userId")
        return chats.addMember(id, me.userId, userId)
    }

    // ---------------------------------------------------------------- беседа: название и аватар (группы)

    /** {name} — переименовать групповую беседу (любой участник). */
    @PATCH
    @Path("/{id}")
    fun rename(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID, req: ChatPatchIn?): ChatDetailsOut {
        val me = currentUser.require(authorization).userId
        actions.rename(me, id, req ?: ChatPatchIn())
        return chats.details(id, me)
    }

    /** Аватар беседы файлом: multipart, поле file (картинка до 5 МБ). */
    @PUT
    @Path("/{id}/avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadAvatar(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID, @RestForm("file") file: FileUpload?): ChatDetailsOut {
        val me = currentUser.require(authorization).userId
        val f = file ?: throw ApiException.badRequest("no_file", "нужно поле file (multipart/form-data)")
        val img = media.upload(me, f.uploadedFile(), f.size(), onlyImages = true, maxOverride = 5L * 1024 * 1024)
        actions.setAvatar(me, id, img.id)
        return chats.details(id, me)
    }

    /** Аватар беседы из уже загруженной картинки: {mediaId}. */
    @PUT
    @Path("/{id}/avatar")
    @Consumes(MediaType.APPLICATION_JSON)
    fun setAvatar(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID, req: ChatAvatarIn?): ChatDetailsOut {
        val me = currentUser.require(authorization).userId
        actions.setAvatar(me, id, req?.mediaId ?: throw ApiException.badRequest("invalid_media", "нужен mediaId"))
        return chats.details(id, me)
    }

    @DELETE
    @Path("/{id}/avatar")
    fun deleteAvatar(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID): ChatDetailsOut {
        val me = currentUser.require(authorization).userId
        actions.clearAvatar(me, id)
        return chats.details(id, me)
    }

    // ---------------------------------------------------------------- пересылка и реакции

    /** {messageIds, toChatIds?, toUserIds?, comment?} — переслать сообщения этого чата. */
    @POST
    @Path("/{id}/messages/forward")
    fun forward(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID, req: ForwardIn?): ForwardResultOut =
        actions.forward(currentUser.require(authorization).userId, id, req ?: ForwardIn())

    /**
     * Начать звонок в беседе ({kind: audio|video}) или войти в уже идущий.
     * → { call, livekit: { url, token, room, identity } } — дальше `room.connect(url, token)`.
     */
    @POST
    @Path("/{id}/call")
    fun startCall(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID, req: CallStartIn?): CallJoinOut =
        calls.start(currentUser.require(authorization).userId, id, req?.kind)

    /** Идущий звонок беседы (для плашки «идёт звонок») или 204. */
    @GET
    @Path("/{id}/call")
    fun activeCall(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID): Response =
        calls.activeInChat(currentUser.require(authorization).userId, id)
            ?.let { Response.ok(it).build() } ?: Response.noContent().build()

    /** Удалить выбранные (только свои). */
    @POST
    @Path("/{id}/messages/delete")
    fun deleteMany(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID, req: MessageIdsIn?): DeleteManyOut =
        actions.deleteMany(currentUser.require(authorization).userId, id, req ?: MessageIdsIn())

    /** Поставить реакцию (эмодзи в пути, url-encoded). То же по сокету: reaction.add. */
    @PUT
    @Path("/{id}/messages/{messageId}/reactions/{emoji}")
    fun react(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: UUID, @PathParam("messageId") messageId: UUID, @PathParam("emoji") emoji: String,
    ): MessageReactionsOut = actions.react(currentUser.require(authorization).userId, id, messageId, emoji, add = true)

    @DELETE
    @Path("/{id}/messages/{messageId}/reactions/{emoji}")
    fun unreact(
        @HeaderParam("Authorization") authorization: String?,
        @PathParam("id") id: UUID, @PathParam("messageId") messageId: UUID, @PathParam("emoji") emoji: String,
    ): MessageReactionsOut = actions.react(currentUser.require(authorization).userId, id, messageId, emoji, add = false)

    /** Выйти из группы. */
    @DELETE
    @Path("/{id}/members/me")
    fun leave(@HeaderParam("Authorization") authorization: String?, @PathParam("id") id: UUID): Response {
        chats.leave(id, currentUser.require(authorization).userId)
        return Response.noContent().build()
    }
}

@Path("/api/friends")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class FriendResource(
    private val currentUser: CurrentUser,
    private val friends: FriendService,
) {
    /** {friends, incoming, outgoing} */
    @GET
    fun list(@HeaderParam("Authorization") authorization: String?): FriendsOut =
        friends.list(currentUser.require(authorization).userId)

    /** Отправить заявку или принять входящую. */
    @PUT
    @Path("/{userId}")
    fun add(@HeaderParam("Authorization") authorization: String?, @PathParam("userId") userId: UUID): FriendStateOut =
        friends.requestOrAccept(currentUser.require(authorization).userId, userId)

    /** Удалить из друзей / отклонить / отменить заявку. */
    @DELETE
    @Path("/{userId}")
    fun remove(@HeaderParam("Authorization") authorization: String?, @PathParam("userId") userId: UUID): FriendStateOut =
        friends.remove(currentUser.require(authorization).userId, userId)
}
