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
import org.example.service.EventService
import org.example.service.PostService
import java.util.UUID

/**
 * Действия с записью — одни и те же из ленты, «свежего из сообществ» и
 * страницы сообщества.
 */
@Path("/api/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class PostResource(
    private val currentUser: CurrentUser,
    private val posts: PostService,
) {
    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): PostOut = posts.get(id, me(auth))

    /** Автор: {title?, body?, meta?} */
    @PATCH
    @Path("/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: PostIn?): PostOut =
        posts.update(me(auth), id, req ?: PostIn())

    /** Автор или admin сообщества. */
    @DELETE
    @Path("/{id}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        posts.delete(me(auth), id)
        return Response.noContent().build()
    }

    /** admin: {pinned} */
    @PUT
    @Path("/{id}/pin")
    fun pin(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: PinIn?): PostOut =
        posts.pin(me(auth), id, req?.pinned ?: true)

    /** Стрелочка: 5 в сутки, не отзывается. 409 — уже, 429 — кончились. */
    @POST
    @Path("/{id}/upvote")
    fun upvote(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): UpvoteOut = posts.upvote(me(auth), id)

    @POST
    @Path("/{id}/like")
    fun like(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): LikeOut = posts.like(me(auth), id)

    @DELETE
    @Path("/{id}/like")
    fun unlike(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): LikeOut = posts.unlike(me(auth), id)

    /** «Читаю»: при показе записи и раз в 2–3 минуты, пока она на экране. */
    @POST
    @Path("/{id}/read")
    fun read(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): ReadOut = posts.read(me(auth), id)

    /**
     * Поделиться: {chatId?, chatIds?, userId?, userIds?, comment?} — запись уходит
     * сообщением в чаты и/или людям в личку (до 20 адресатов). Работает для записей
     * сообществ, пульса и стены одинаково.
     */
    @POST
    @Path("/{id}/share")
    fun share(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: ShareIn?): ShareOut =
        posts.share(me(auth), id, req ?: ShareIn())

    /** Все комментарии по времени, с parentId; ?after=<createdAt> — дальше. */
    @GET
    @Path("/{id}/comments")
    fun comments(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("id") id: UUID,
        @QueryParam("after") after: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
    ): CommentsPageOut = posts.comments(id, me(auth), parseInstantParam(after, "after"), limit)

    /** {body, parentId?} — ответ на запись или на комментарий. */
    @POST
    @Path("/{id}/comments")
    fun comment(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: CommentIn?): Response =
        Response.status(201).entity(posts.comment(me(auth), id, req ?: CommentIn())).build()

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}

@Path("/api/comments")
@Produces(MediaType.APPLICATION_JSON)
class CommentResource(
    private val currentUser: CurrentUser,
    private val posts: PostService,
) {
    /** Автор или admin сообщества. */
    @DELETE
    @Path("/{id}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        posts.deleteComment(currentUser.require(auth).userId, id)
        return Response.noContent().build()
    }
}

/** Лента и личное: что я лайкнул, сколько апвоутов осталось. */
@Path("/api/feed")
@Produces(MediaType.APPLICATION_JSON)
class FeedResource(
    private val currentUser: CurrentUser,
    private val posts: PostService,
) {
    /** Новое из сообществ, где я участник или «читаю без вступления». ?before=<createdAt>. */
    @GET
    fun feed(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): PostPageOut = posts.feed(currentUser.require(auth).userId, parseInstantParam(before, "before"), limit)

    /** Понравившееся (лайки). */
    @GET
    @Path("/liked")
    fun liked(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): PostPageOut = posts.liked(currentUser.require(auth).userId, parseInstantParam(before, "before"), limit)

    @GET
    @Path("/upvote-budget")
    fun budget(@HeaderParam("Authorization") auth: String?): UpvoteBudgetOut =
        posts.upvoteBudget(currentUser.require(auth).userId)
}

/** События по id: записаться, отменить, список идущих. */
@Path("/api/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class EventResource(
    private val currentUser: CurrentUser,
    private val events: EventService,
) {
    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): EventOut = events.get(id, me(auth))

    /** admin: {title?, description?, location?, startsAt?, endsAt?} */
    @PATCH
    @Path("/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: EventIn?): EventOut =
        events.update(me(auth), id, req ?: EventIn())

    /** admin: отменить (записавшимся придёт уведомление). */
    @DELETE
    @Path("/{id}")
    fun cancel(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        events.cancel(me(auth), id)
        return Response.noContent().build()
    }

    /** «Пойду» — участник; придёт уведомление, за час до начала — напоминание. */
    @POST
    @Path("/{id}/register")
    fun register(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): EventOut = events.register(me(auth), id)

    @DELETE
    @Path("/{id}/register")
    fun unregister(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): EventOut = events.unregister(me(auth), id)

    @GET
    @Path("/{id}/attendees")
    fun attendees(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): List<UserShortOut> {
        me(auth)
        return events.attendees(id)
    }

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
