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
import org.example.service.VoiceService
import java.util.UUID

/** Вкладка «Голос» сообщества: каналы и кто в них. */
@Path("/api/communities/{slug}/voice")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class CommunityVoiceResource(
    private val currentUser: CurrentUser,
    private val voice: VoiceService,
) {
    @GET
    fun list(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): VoiceCommunityOut =
        voice.list(me(auth), slug)

    /** admin: {name, maxTalkers?} → 201 */
    @POST
    fun create(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: VoiceChannelIn?): Response =
        Response.status(201).entity(voice.create(me(auth), slug, req ?: VoiceChannelIn())).build()

    /** admin: {channelIds: [...все каналы...]} */
    @PUT
    @Path("/order")
    fun reorder(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String, req: VoiceOrderIn?): VoiceCommunityOut =
        voice.reorder(me(auth), slug, req?.channelIds ?: emptyList())

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}

/** Голосовой канал: войти/выйти, модерация, голос событий и обсуждений. */
@Path("/api/voice")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class VoiceResource(
    private val currentUser: CurrentUser,
    private val voice: VoiceService,
) {
    /** Голос события (создаётся сам). */
    @GET
    @Path("/event/{eventId}")
    fun forEvent(@HeaderParam("Authorization") auth: String?, @PathParam("eventId") eventId: UUID): VoiceChannelOut =
        voice.forEvent(me(auth), eventId)

    /** Голос обсуждения (создаётся сам). */
    @GET
    @Path("/discussion/{chatId}")
    fun forDiscussion(@HeaderParam("Authorization") auth: String?, @PathParam("chatId") chatId: UUID): VoiceChannelOut =
        voice.forDiscussion(me(auth), chatId)

    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): VoiceChannelOut {
        me(auth)
        return voice.get(id)
    }

    @PATCH
    @Path("/{id}")
    fun update(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: VoiceChannelIn?): VoiceChannelOut =
        voice.update(me(auth), id, req ?: VoiceChannelIn())

    @DELETE
    @Path("/{id}")
    fun delete(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        voice.delete(me(auth), id)
        return Response.noContent().build()
    }

    /** {mode: talk | watch} → { channel, livekit: { url, token, room, identity } } */
    @POST
    @Path("/{id}/join")
    fun join(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: VoiceJoinIn?): VoiceJoinOut =
        voice.join(me(auth), id, req?.mode)

    @POST
    @Path("/{id}/leave")
    fun leave(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        voice.leave(me(auth), id)
        return Response.noContent().build()
    }

    @POST
    @Path("/{id}/participants/{userId}/kick")
    fun kick(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("userId") userId: UUID): Response {
        voice.kick(me(auth), id, userId)
        return Response.noContent().build()
    }

    /** {muted: true|false} — отключить/вернуть микрофон (admin). */
    @PUT
    @Path("/{id}/participants/{userId}/mute")
    fun mute(
        @HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("userId") userId: UUID, req: VoiceMuteIn?,
    ): VoiceChannelOut = voice.mute(me(auth), id, userId, req?.muted ?: true)

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
