package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.ChatFolderService
import java.util.UUID

/** Папки чатов. Чаты папки — GET /api/chats?folder={id}. */
@Path("/api/chats/folders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ChatFolderResource(
    private val currentUser: CurrentUser,
    private val folders: ChatFolderService,
) {
    @GET
    fun list(@HeaderParam("Authorization") auth: String?): List<ChatFolderOut> = folders.list(me(auth))

    /** {title, emoji?, chatIds?} → 201 */
    @POST
    fun create(@HeaderParam("Authorization") auth: String?, req: ChatFolderIn?): Response =
        Response.status(201).entity(folders.create(me(auth), req ?: ChatFolderIn())).build()

    /** Порядок папок: {folderIds: [...все мои...]} */
    @PUT
    @Path("/order")
    fun reorder(@HeaderParam("Authorization") auth: String?, req: ChatFolderOrderIn?): List<ChatFolderOut> =
        folders.reorder(me(auth), req?.folderIds ?: emptyList())

    /** {title?, emoji?, chatIds?} — chatIds заменяет состав целиком. */
    @PATCH
    @Path("/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: ChatFolderIn?): ChatFolderOut =
        folders.update(me(auth), id, req ?: ChatFolderIn())

    @DELETE
    @Path("/{id}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        folders.delete(me(auth), id)
        return Response.noContent().build()
    }

    @PUT
    @Path("/{id}/chats/{chatId}")
    fun addChat(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("chatId") chatId: UUID): ChatFolderOut =
        folders.addChat(me(auth), id, chatId)

    @DELETE
    @Path("/{id}/chats/{chatId}")
    fun removeChat(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("chatId") chatId: UUID): ChatFolderOut =
        folders.removeChat(me(auth), id, chatId)

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
