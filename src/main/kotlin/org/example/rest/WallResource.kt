package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.PostService

/**
 * Стена: записи человека у себя. Это обычные записи — лайк, апвоут, комментарии,
 * «поделиться» (в том числе в личку), закрепить, править, удалить — через /api/posts/{id}/…
 */
@Path("/api/wall")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class WallResource(
    private val currentUser: CurrentUser,
    private val posts: PostService,
) {
    /** Стена по username: закреплённые (на первой странице) + новые сверху; дальше ?before=<createdAt последней>. */
    @GET
    @Path("/{username}")
    fun wall(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("username") username: String,
        @QueryParam("before") before: String?,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
    ): PostPageOut = posts.wall(username, currentUser.require(auth).userId, parseInstantParam(before, "before"), limit)

    /** {title?, body?, mediaIds?: [до 4 картинок или 1 видео], trackIds?} → 201 запись. */
    @POST
    fun create(@HeaderParam("Authorization") auth: String?, req: WallPostIn?): Response =
        Response.status(201).entity(posts.createWall(currentUser.require(auth).userId, req ?: WallPostIn())).build()
}
