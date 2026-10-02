package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.example.service.NotificationService
import java.util.UUID

data class NotificationIdsIn(val ids: List<UUID> = emptyList())

/**
 * Уведомления. Живые приходят кадром `notification.new` в сокет; при любой
 * смене прочитанности во все вкладки уходит `notification.state {unread}`.
 * Все изменяющие ручки отвечают `{unread}` — новым значением счётчика.
 */
@Path("/api/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class NotificationResource(
    private val currentUser: CurrentUser,
    private val notifications: NotificationService,
) {
    /** ?status=all|unread|read (unreadOnly=true — старый вариант), ?before=<createdAt>, ?limit=30 */
    @GET
    fun list(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("status") status: String?,
        @QueryParam("unreadOnly") @DefaultValue("false") unreadOnly: Boolean,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
    ): NotificationsPageOut = notifications.list(
        me(auth),
        status ?: if (unreadOnly) "unread" else "all",
        parseInstantParam(before, "before"),
        limit,
    )

    @GET
    @Path("/unread-count")
    fun unreadCount(@HeaderParam("Authorization") auth: String?): Map<String, Long> =
        mapOf("unread" to notifications.unreadCount(me(auth)))

    @POST
    @Path("/{id}/read")
    fun read(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Map<String, Long> =
        mapOf("unread" to notifications.markRead(me(auth), id))

    @POST
    @Path("/{id}/unread")
    fun unread(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Map<String, Long> =
        mapOf("unread" to notifications.markUnread(me(auth), id))

    /** {ids: [...]} — прочитать пачкой. */
    @POST
    @Path("/read")
    fun readMany(@HeaderParam("Authorization") auth: String?, req: NotificationIdsIn?): Map<String, Long> =
        mapOf("unread" to notifications.markReadMany(me(auth), req?.ids ?: emptyList()))

    @POST
    @Path("/read-all")
    fun readAll(@HeaderParam("Authorization") auth: String?): Map<String, Long> =
        mapOf("unread" to notifications.markAllRead(me(auth)))

    @DELETE
    @Path("/{id}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Map<String, Long> =
        mapOf("unread" to notifications.delete(me(auth), id))

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
