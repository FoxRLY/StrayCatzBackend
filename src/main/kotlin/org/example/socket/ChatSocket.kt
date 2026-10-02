package org.example

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.logging.Log
import io.quarkus.websockets.next.CloseReason
import io.quarkus.websockets.next.OnClose
import io.quarkus.websockets.next.OnOpen
import io.quarkus.websockets.next.OnTextMessage
import io.quarkus.websockets.next.WebSocket
import io.quarkus.websockets.next.WebSocketConnection
import io.smallrye.common.annotation.Blocking
import org.example.auth.AuthResult
import org.example.auth.AuthService
import org.example.bus.EventBus
import org.example.proto.*
import org.example.registry.ConnState
import org.example.registry.ConnectionRegistry
import org.example.service.CallException
import org.example.service.CallService
import org.example.service.ChatActionsService
import org.example.service.ChatService
import org.example.service.MessageExtras
import org.example.service.ForbiddenException
import org.example.service.MessageService
import org.example.service.MessageValidationException
import org.example.service.PresenceService
import java.util.UUID

/**
 * Один сокет на вкладку: ws(s)://host/v1/ws.
 *
 * Все обработчики БЛОКИРУЮЩИЕ (@Blocking, worker-пул): внутри JDBC/Hibernate.
 * В прошлой версии они были `suspend` — websockets-next считает такие
 * методы неблокирующими и гоняет их на event loop, а Hibernate ORM на
 * event loop бросает "blocking operation on IO thread".
 */
@WebSocket(path = "/v1/ws")
class ChatSocket(
    private val connection: WebSocketConnection,
    private val mapper: ObjectMapper,
    private val auth: AuthService,
    private val registry: ConnectionRegistry,
    private val bus: EventBus,
    private val chats: ChatService,
    private val messages: MessageService,
    private val presence: PresenceService,
    private val calls: CallService,
    private val chatActions: ChatActionsService,
) {
    companion object {
        const val MAX_CONNECTIONS_PER_USER = 5
    }

    private val codec = EnvelopeCodec(mapper)

    @OnOpen
    @Blocking
    fun onOpen() {
        val header = connection.handshakeRequest().header("Sec-WebSocket-Protocol")
        val ticket = when (val r = auth.resolve(header)) {
            is AuthResult.Ok -> r.ticket
            AuthResult.Blocked -> return close(CloseCodes.FORBIDDEN, "user blocked")
            AuthResult.Missing, AuthResult.Invalid -> return close(CloseCodes.UNAUTHORIZED, "bad or expired token")
        }
        if (registry.connectionCount(ticket.userId) >= MAX_CONNECTIONS_PER_USER) {
            return close(CloseCodes.TOO_MANY_CONNECTIONS, "too many connections")
        }

        val firstConnection = registry.connectionCount(ticket.userId) == 0
        registry.put(ConnState(connection.id(), ticket.userId, ticket.username))
        if (firstConnection) presence.connected(ticket.userId)
        // ready шлём в ответ на hello
    }

    @OnClose
    @Blocking
    fun onClose() {
        val st = registry.get(connection.id()) ?: return
        val left = registry.remove(connection.id())
        if (left == 0) {
            // последняя вкладка на этой ноде — гасим presence и звонки
            runCatching { calls.leaveAll(st.userId) }.onFailure { Log.error("leaveAll", it) }
            runCatching { presence.disconnected(st.userId) }.onFailure { Log.error("presence offline", it) }
        }
    }

    @OnTextMessage
    @Blocking
    fun onMessage(raw: String) {
        val st = registry.get(connection.id()) ?: return close(CloseCodes.UNAUTHORIZED, "no session")

        val env = try {
            codec.parse(raw)
        } catch (e: Exception) {
            Log.debugf("не смогли распарсить кадр: %s | raw=%s", e.message, raw.take(500))
            return sendError(null, ErrorCodes.BAD_FRAME, "не смогли распарсить кадр: ${e.message?.take(200)}")
        }

        try {
            when (env.t) {
                FrameTypes.HELLO -> handleHello(st, env)
                FrameTypes.CHAT_OPEN -> handleChatOpen(st, env)
                FrameTypes.CHAT_CLOSE -> st.openChats.remove(codec.payloadAs(env, ChatCloseIn::class.java).chatId)
                FrameTypes.MESSAGE_SEND -> handleMessageSend(st, env)
                FrameTypes.MESSAGE_EDIT -> handleMessageEdit(st, env)
                FrameTypes.MESSAGE_DELETE -> handleMessageDelete(st, env)
                FrameTypes.MESSAGE_READ -> handleMessageRead(st, env)
                FrameTypes.TYPING -> handleTyping(st, env)
                FrameTypes.PRESENCE_SET -> handlePresenceSet(st, env)
                FrameTypes.PRESENCE_QUERY -> handlePresenceQuery(st, env)
                FrameTypes.ROOM_OPEN -> st.openRooms.add(codec.payloadAs(env, RoomOpenIn::class.java).ownerId)
                FrameTypes.ROOM_CLOSE -> st.openRooms.remove(codec.payloadAs(env, RoomOpenIn::class.java).ownerId)
                FrameTypes.PING -> send(FrameTypes.PONG, null, env.rid)
                FrameTypes.REACTION_ADD, FrameTypes.REACTION_REMOVE -> handleReaction(st, env)

                FrameTypes.CALL_INVITE -> handleCallInvite(st, env)
                FrameTypes.CALL_ACCEPT -> handleCallAccept(st, env)
                FrameTypes.CALL_DECLINE -> handleCallDecline(st, env)
                FrameTypes.CALL_LEAVE -> handleCallLeave(st, env)
                FrameTypes.CALL_SIGNAL -> handleCallSignal(st, env)

                else -> sendError(env.rid, ErrorCodes.UNKNOWN_TYPE, "неизвестный тип кадра: ${env.t}")
            }
        } catch (e: IllegalArgumentException) {
            // сюда же падают ошибки Jackson при разборе d (MismatchedInput и т.п.)
            Log.debugf("некорректный d для %s: %s", env.t, e.message)
            sendError(env.rid, ErrorCodes.BAD_FRAME, "некорректный d для ${env.t}: ${e.message?.take(200)}")
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            Log.debugf("некорректный d для %s: %s", env.t, e.originalMessage)
            sendError(env.rid, ErrorCodes.BAD_FRAME, "некорректный d для ${env.t}: ${e.originalMessage?.take(200)}")
        } catch (e: org.example.rest.ApiException) {
            // ошибки из общих сервисов (например, вложения): тот же code, что и в REST
            sendError(env.rid, e.code, e.message ?: e.code)
        } catch (e: MessageValidationException) {
            sendError(env.rid, ErrorCodes.BAD_FRAME, e.message ?: "validation error")
        } catch (e: ForbiddenException) {
            sendError(env.rid, ErrorCodes.FORBIDDEN, e.message ?: "forbidden")
        } catch (e: CallException) {
            sendError(env.rid, ErrorCodes.CALL_ERROR, e.message ?: "call error")
        } catch (e: Exception) {
            Log.error("не смогли обработать кадр ${env.t}", e)
            sendError(env.rid, ErrorCodes.INTERNAL, "внутренняя ошибка")
        }
    }

    // ---------------------------------------------------------------- handlers

    private fun handleHello(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, HelloIn::class.java)

        st.memberChats.clear()
        st.memberChats.addAll(chats.memberChatIds(st.userId))

        val ready = ReadyOut(
            me = MeOut(st.userId, st.username),
            chats = chats.summaries(st.userId),
            missedFrom = chats.missedFrom(st.userId, d.chats.ifEmpty { st.memberChats.toList() }),
            presence = presence.friendsSnapshot(st.userId),
        )
        send(FrameTypes.READY, ready, env.rid)
    }

    private fun handleChatOpen(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, ChatOpenIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        st.openChats.add(d.chatId)
    }

    private fun handleMessageSend(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, MessageSendIn::class.java)
        val rid = env.rid ?: return sendError(null, ErrorCodes.BAD_FRAME, "message.send обязан нести rid")
        if (!requireMember(st, d.chatId, rid)) return
        if (!st.sendBucket.tryTake()) {
            return sendError(rid, ErrorCodes.RATE_LIMITED, "не чаще 10 message.send в секунду")
        }
        val ack = messages.send(
            d.chatId, st.userId, d.body, d.mediaId, d.clientToken, rid, mediaIds = d.mediaIds, trackIds = d.trackIds,
            extras = MessageExtras(replyToId = d.replyToId, gifId = d.gifId, stickerId = d.stickerId),
        )
        send(FrameTypes.MESSAGE_ACK, ack, rid)
    }

    private fun handleReaction(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, ReactionIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        // всем участникам (и этому соединению) уйдёт message.reactions
        chatActions.react(st.userId, d.chatId, d.messageId, d.emoji, add = env.t == FrameTypes.REACTION_ADD)
    }

    private fun handleMessageEdit(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, MessageEditIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        messages.edit(d.chatId, d.messageId, st.userId, d.body)
    }

    private fun handleMessageDelete(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, MessageDeleteIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        messages.delete(d.chatId, d.messageId, st.userId)
    }

    private fun handleMessageRead(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, MessageReadIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        messages.markRead(d.chatId, st.userId, d.seq)
    }

    private fun handleTyping(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, TypingIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        val frame = Envelope(t = FrameTypes.TYPING, d = mapper.valueToTree(TypingOut(d.chatId, st.userId)))
        bus.publishToChat(d.chatId, frame, liveOnly = true)
    }

    private fun handlePresenceSet(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, PresenceSetIn::class.java)
        presence.update(st.userId, d.status, d.doing, d.trackId, d.positionSec)
    }

    private fun handlePresenceQuery(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, PresenceQueryIn::class.java)
        send(FrameTypes.PRESENCE_SNAPSHOT, PresenceSnapshotOut(presence.query(st.userId, d.userIds)), env.rid)
    }

    // ---------------------------------------------------------------- звонки

    private fun handleCallInvite(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallInviteIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.invite(d.chatId, d.callId, d.kind, st.userId)
    }

    private fun handleCallAccept(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallAcceptIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.accept(d.chatId, d.callId, st.userId)
    }

    private fun handleCallDecline(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallDeclineIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.decline(d.chatId, d.callId, st.userId, d.reason)
    }

    private fun handleCallLeave(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallLeaveIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.leave(d.chatId, d.callId, st.userId)
    }

    private fun handleCallSignal(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallSignalIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.signal(d.chatId, d.callId, st.userId, d.toUserId, d.kind, d.payload)
    }

    // ---------------------------------------------------------------- utils

    private fun requireMember(st: ConnState, chatId: UUID, rid: String?): Boolean {
        // Всегда смотрим в БД (один запрос по PK): через REST человека могут
        // добавить в чат или он может из него выйти — кэш на соединении врал бы.
        if (chats.isMember(chatId, st.userId)) {
            st.memberChats.add(chatId)
            return true
        }
        st.memberChats.remove(chatId)
        st.openChats.remove(chatId)
        sendError(rid, ErrorCodes.NOT_A_MEMBER, "нет доступа к этой беседе")
        close(CloseCodes.FORBIDDEN, "no access to chat")
        return false
    }

    private fun send(t: String, payload: Any?, rid: String? = null) {
        connection.sendTextAndAwait(codec.server(t, payload, rid))
    }

    private fun sendError(rid: String?, code: String, message: String) {
        send(FrameTypes.ERROR, ErrorOut(rid, code, message), rid)
    }

    private fun close(code: Int, reason: String) {
        Log.debugf("закрываем %s: %d %s", connection.id(), code, reason)
        connection.closeAndAwait(CloseReason(code, reason))
    }
}
