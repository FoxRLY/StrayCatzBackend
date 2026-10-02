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
import org.example.service.PulseService
import java.util.UUID

/**
 * Пульс. Лайк, апвоут, комментарии, репост в чат, прочтение, удаление —
 * общие для всех записей: /api/posts/{id}/…
 */
@Path("/api/pulse")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class PulseResource(
    private val currentUser: CurrentUser,
    private val pulse: PulseService,
) {
    /**
     * ?sort=hot|new|friends|week (по умолчанию hot), ?limit=20.
     * Дальше: для new/friends — ?before=<nextBefore>, для hot/week — ?offset=<nextOffset>.
     */
    @GET
    fun list(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("sort") sort: String?,
        @QueryParam("before") before: String?,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
        @QueryParam("limit") @DefaultValue("20") limit: Int,
        @QueryParam("tag") tag: String?,
    ): PulsePageOut = pulse.list(currentUser.require(auth).userId, sort, parseInstantParam(before, "before"), offset, limit, tag)

    /**
     * {body?, mediaIds?: [..до 4], poll?: {options, multiple?, closesInHours?},
     *  communitySlug?, asCommunity?} -> 201 запись. Картинки/гифки/видео — сначала POST /api/media.
     */
    @POST
    fun create(@HeaderParam("Authorization") auth: String?, req: PulseIn?): Response =
        Response.status(201).entity(pulse.create(currentUser.require(auth).userId, req ?: PulseIn())).build()

    /** {optionIds: [...]} — голос (заменяет прошлый; пустой список — отозвать). Ответ — запись целиком. */
    @POST
    @Path("/{id}/vote")
    fun vote(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: PollVoteIn?): PostOut =
        pulse.vote(currentUser.require(auth).userId, id, req?.optionIds ?: emptyList())
}
