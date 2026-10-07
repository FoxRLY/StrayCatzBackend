package org.example.rest

import io.quarkus.logging.Log
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.CallService
import org.example.service.LiveKitClient
import java.util.UUID

/**
 * Звонки (LiveKit). Начать — POST /api/chats/{chatId}/call, остальное — здесь.
 */
@Path("/api/calls")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class CallResource(
    private val currentUser: CurrentUser,
    private val calls: CallService,
) {
    @GET
    @Path("/{id}")
    fun get(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): CallOut = calls.get(me(auth), id)

    /** Принять входящий / войти в идущий / вернуться → токен LiveKit. */
    @POST
    @Path("/{id}/join")
    fun join(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): CallJoinOut = calls.join(me(auth), id)

    @POST
    @Path("/{id}/decline")
    fun decline(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: CallDeclineIn?): Response {
        calls.decline(me(auth), id, req?.reason)
        return Response.noContent().build()
    }

    /** Положить трубку (после room.disconnect() на клиенте). */
    @POST
    @Path("/{id}/leave")
    fun leave(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        calls.leave(me(auth), id)
        return Response.noContent().build()
    }

    /** Выгнать участника (начавший звонок или создатель беседы). */
    @POST
    @Path("/{id}/participants/{userId}/kick")
    fun kick(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, @PathParam("userId") userId: UUID): Response {
        calls.kick(me(auth), id, userId)
        return Response.noContent().build()
    }

    /** Позвонить человеку по id (из профиля/комнаты): личка найдётся или создастся. {kind} → CallJoinOut. */
    @POST
    @Path("/to/{userId}")
    fun callUser(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID, req: CallStartIn?): CallJoinOut =
        calls.callUser(me(auth), userId, req?.kind)

    /** То же по логину — кнопка «позвонить» в чужой комнате /rooms/{username}. */
    @POST
    @Path("/to-username/{username}")
    fun callUsername(@HeaderParam("Authorization") auth: String?, @PathParam("username") username: String, req: CallStartIn?): CallJoinOut =
        calls.callUsername(me(auth), username, req?.kind)

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}

/**
 * Вебхуки LiveKit (webhook.urls в livekit.yaml). Без нашей авторизации:
 * подлинность — по подписи (JWT нашим api-secret + sha256 тела).
 */
@Path("/api/hooks/livekit")
class LiveKitWebhookResource(
    private val lk: LiveKitClient,
    private val calls: CallService,
    private val voice: org.example.service.VoiceService,
) {
    @POST
    @Consumes(MediaType.WILDCARD)
    fun receive(@HeaderParam("Authorization") authorization: String?, body: String?): Response {
        val event = lk.verifyWebhook(authorization, body.orEmpty())
            ?: return Response.status(401).build()
        try {
            // комнаты голосовых каналов — voice-<id>, звонков — call-<id>
            if (event.path("room").path("name").asText().startsWith(org.example.service.VoiceService.ROOM_PREFIX)) voice.onWebhook(event)
            else calls.onWebhook(event)
        } catch (e: Exception) {
            // 5xx — LiveKit повторит; ошибки в данных повторами не лечатся, поэтому 200 и лог
            Log.warnf("вебхук LiveKit %s: %s", event.path("event").asText(), e.message)
        }
        return Response.ok().build()
    }
}
