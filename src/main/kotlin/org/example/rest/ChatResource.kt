package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.ChatManagementService
import org.example.service.FriendService
import java.util.UUID

@Path("/api/chats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ChatResource(
    private val currentUser: CurrentUser,
    private val chats: ChatManagementService,
) {

    /** Мои чаты: последнее сообщение, непрочитанные, собеседник в личке. */
    @GET
    fun list(@HeaderParam("Authorization") authorization: String?): List<ChatListItemOut> =
        chats.listMine(currentUser.require(authorization).userId)

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
