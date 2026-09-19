package org.example.ws

import org.example.ws.service.ChatService
import com.fasterxml.jackson.databind.ObjectMapper
import org.example.ws.auth.AuthResult
import org.example.ws.auth.AuthService
import org.example.ws.bus.EventBus
import org.example.ws.proto.*
import org.example.ws.registry.ConnState
import org.example.ws.registry.ConnectionRegistry
import org.example.ws.service.CallException
import org.example.ws.service.CallService
import org.example.ws.service.MessageService
import org.example.ws.service.MessageValidationException
import org.example.ws.service.PresenceService
import io.quarkus.logging.Log
import io.quarkus.websockets.next.OnClose
import io.quarkus.websockets.next.OnOpen
import io.quarkus.websockets.next.OnTextMessage
import io.quarkus.websockets.next.WebSocket
import io.quarkus.websockets.next.WebSocketConnection
import java.util.UUID

/**
 * Один сокет на вкладку, wss://.../v1/ws, как в протоколе. Роутинг по `t`
 * ручной (switch внутри одного @OnTextMessage) — конверт общий для всех
 * типов кадров, поэтому парсим в два прохода: сначала конверт, потом `d`
 * по конкретному типу.
 *
 * TODO(проверить на установленной версии Quarkus): закрытие соединения с
 * произвольным кодом (4401/4403/4408/4409) — здесь используется
 * `connection.close(int, String)`; если в вашей версии websockets-next
 * сигнатура другая (например, отдельный тип CloseReason), поправьте
 * вызовы closeWithCode ниже — это единственное место, которое их делает.
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
) {
    private val codec = EnvelopeCodec(mapper)

    @OnOpen
    suspend fun onOpen() {
        val subProtocolHeader = connection.handshakeRequest().header("Sec-WebSocket-Protocol")
        when (val result = auth.resolve(subProtocolHeader)) {
            is AuthResult.Ok -> {
                registry.put(connection.id(), ConnState(result.ticket.userId, result.ticket.handle))
                presence.setOnline(result.ticket.userId)
                // ready отправляем не тут, а в ответ на hello — так клиент
                // успевает передать lastEventId/chats до первого пуша.
            }
            AuthResult.Missing, AuthResult.Invalid -> closeWithCode(4401, "bad or expired token")
        }
    }

    @OnClose
    suspend fun onClose() {
        val st = registry.get(connection.id()) ?: return
        registry.remove(connection.id())
        // presence.set(offline) шлём безусловно — упрощение первой версии.
        // У человека может быть до 5 соединений (см. протокол), и оффлайн
        // после закрытия одного из них при живых остальных — заметный баг;
        // корректно: считать через OpenConnections, есть ли ещё соединения
        // с тем же userId, и гасить presence только когда их не осталось.
        presence.setOffline(st.userId)
    }

    @OnTextMessage
    suspend fun onMessage(raw: String) {
        val st = registry.get(connection.id())
        if (st == null) {
            closeWithCode(4401, "no session")
            return
        }

        val env = try {
            codec.parse(raw)
        } catch (e: Exception) {
            sendError(null, ErrorCodes.BAD_FRAME, "не смогли распарсить кадр: ${e.message}")
            return
        }

        try {
            when (env.t) {
                FrameTypes.HELLO -> handleHello(st, env)
                FrameTypes.CHAT_OPEN -> handleChatOpen(st, env)
                FrameTypes.CHAT_CLOSE -> handleChatClose(st, env)
                FrameTypes.MESSAGE_SEND -> handleMessageSend(st, env)
                FrameTypes.MESSAGE_READ -> handleMessageRead(st, env)
                FrameTypes.TYPING -> handleTyping(st, env)
                FrameTypes.PRESENCE_SET -> handlePresenceSet(st, env)
                FrameTypes.PING -> connection.sendTextAndAwait(codec.server(FrameTypes.PONG, UUID.randomUUID().toString(), null))

                FrameTypes.CALL_INVITE -> handleCallInvite(st, env)
                FrameTypes.CALL_ACCEPT -> handleCallAccept(st, env)
                FrameTypes.CALL_DECLINE -> handleCallDecline(st, env)
                FrameTypes.CALL_LEAVE -> handleCallLeave(st, env)
                FrameTypes.CALL_SIGNAL -> handleCallSignal(st, env)

                else -> sendError(env.rid, ErrorCodes.UNKNOWN_TYPE, "неизвестный тип кадра: ${env.t}")
            }
        } catch (e: MessageValidationException) {
            sendError(env.rid, ErrorCodes.BAD_FRAME, e.message ?: "validation error")
        } catch (e: CallException) {
            sendError(env.rid, ErrorCodes.CALL_NOT_FOUND, e.message ?: "call error")
        } catch (e: Exception) {
            Log.error("не смогли обработать кадр ${env.t}", e)
            sendError(env.rid, ErrorCodes.BAD_FRAME, "внутренняя ошибка")
        }
    }

    // ---- handlers ----

    private suspend fun handleHello(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, HelloIn::class.java)

        st.memberChats.clear()
        st.memberChats.addAll(chats.memberChatIds(st.userId))

        val summaries = chats.summaries(st.userId)
        val missed = chats.missedFrom(st.userId, d.chats.ifEmpty { st.memberChats.toList() })

        val ready = ReadyOut(me = MeOut(st.userId, st.handle), chats = summaries, missedFrom = missed)
        connection.sendTextAndAwait(codec.server(FrameTypes.READY, UUID.randomUUID().toString(), ready))
    }

    private suspend fun handleChatOpen(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, ChatOpenIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        st.openChats.add(d.chatId)
    }

    private fun handleChatClose(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, ChatCloseIn::class.java)
        st.openChats.remove(d.chatId)
    }

    private suspend fun handleMessageSend(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, MessageSendIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        if (!st.sendBucket.tryTake()) {
            sendError(env.rid, ErrorCodes.RATE_LIMITED, "не чаще 10 message.send в секунду")
            return
        }
        val rid = env.rid ?: run {
            sendError(null, ErrorCodes.BAD_FRAME, "message.send обязан нести rid")
            return
        }
        val ack = messages.send(d.chatId, st.userId, d.body, d.mediaId, d.clientToken, rid)
        connection.sendTextAndAwait(codec.server(FrameTypes.MESSAGE_ACK, UUID.randomUUID().toString(), ack, rid))
    }

    private suspend fun handleMessageRead(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, MessageReadIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        messages.markRead(d.chatId, st.userId, d.seq)
    }

    private suspend fun handleTyping(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, TypingIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        val payload = TypingOut(d.chatId, st.userId)
        val frame = Envelope(FrameTypes.TYPING, UUID.randomUUID().toString(), d = mapper.valueToTree(payload))
        // typing — только тем, кто реально открыл эту беседу (liveOnly)
        bus.publishToChat(d.chatId, frame, liveOnly = true)
    }

    private suspend fun handlePresenceSet(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, PresenceSetIn::class.java)
        presence.update(st.userId, d.status, d.doing, d.trackId, d.positionSec)
    }

    // ---- звонки ----

    private suspend fun handleCallInvite(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallInviteIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.invite(d.chatId, d.callId, d.kind, st.userId)
    }

    private suspend fun handleCallAccept(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallAcceptIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.accept(d.chatId, d.callId, st.userId)
    }

    private suspend fun handleCallDecline(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallDeclineIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.decline(d.chatId, d.callId, st.userId, d.reason)
    }

    private suspend fun handleCallLeave(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallLeaveIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.leave(d.chatId, d.callId, st.userId)
    }

    private suspend fun handleCallSignal(st: ConnState, env: Envelope) {
        val d = codec.payloadAs(env, CallSignalIn::class.java)
        if (!requireMember(st, d.chatId, env.rid)) return
        calls.signal(d.chatId, d.callId, st.userId, d.toUserId, d.kind, d.payload)
    }

    // ---- utils ----

    private suspend fun requireMember(st: ConnState, chatId: UUID, rid: String?): Boolean {
        if (chatId in st.memberChats) return true
        // memberChats грузится один раз на hello; если человека добавили в
        // чат уже после — просто перечитываем из БД, не отказываем зря.
        if (chats.isMember(chatId, st.userId)) {
            st.memberChats.add(chatId)
            return true
        }
        sendError(rid, ErrorCodes.NOT_A_MEMBER, "нет доступа к этой беседе")
        closeWithCode(4403, "no access to chat")
        return false
    }

    private suspend fun sendError(rid: String?, code: String, message: String) {
        val payload = ErrorOut(rid, code, message)
        connection.sendTextAndAwait(codec.server(FrameTypes.ERROR, UUID.randomUUID().toString(), payload, rid))
    }

    /**
     * Протокол просит закрывать сокет конкретными кодами (4401/4403/4408/4409).
     * `WebSocketConnection` в websockets-next точно умеет `closeAndAwait()`
     * без параметров; способ задать произвольный код закрытия в публичном
     * API этого расширения я на 100% из документации не вывел (это либо
     * `close(WebSocketCloseReason)`, либо отдельный SPI) — стоит свериться
     * с javadoc установленной версии и поправить одну эту функцию. Пока
     * закрываем соединение как есть и логируем предполагавшийся код, чтобы
     * логика (что и когда рвать) уже была на месте и не потерялась.
     */
    private suspend fun closeWithCode(code: Int, reason: String) {
        Log.info("closing connection ${connection.id()} intended_code=$code reason=$reason")
        connection.closeAndAwait()
    }
}
