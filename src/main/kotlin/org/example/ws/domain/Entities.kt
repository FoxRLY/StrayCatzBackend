package org.example.ws.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class AppUser : PanacheEntityBase {
    @Id
    lateinit var id: UUID
    lateinit var handle: String

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<AppUser, UUID>
}

@Entity
@Table(name = "chats")
class Chat : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "is_group")
    var isGroup: Boolean = false

    var title: String? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<Chat, UUID>
}

@Embeddable
data class ChatMemberId(
    @Column(name = "chat_id") var chatId: UUID = UUID(0, 0),
    @Column(name = "user_id") var userId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "chat_members")
class ChatMember : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: ChatMemberId

    @Column(name = "joined_at")
    var joinedAt: Instant = Instant.now()

    @Column(name = "last_read_seq")
    var lastReadSeq: Long = 0

    companion object : PanacheCompanionBase<ChatMember, ChatMemberId>
}

@Entity
@Table(name = "chat_seq")
class ChatSeqEntity : PanacheEntityBase {
    @Id
    @Column(name = "chat_id")
    lateinit var chatId: UUID

    @Column(name = "next_seq")
    var nextSeq: Long = 1

    companion object : PanacheCompanionBase<ChatSeqEntity, UUID>
}

@Entity
@Table(name = "messages")
class Message : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "chat_id")
    lateinit var chatId: UUID

    var seq: Long = 0

    @Column(name = "author_id")
    lateinit var authorId: UUID

    @Column(name = "client_token")
    lateinit var clientToken: UUID

    var body: String? = null

    @Column(name = "media_id")
    var mediaId: UUID? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "edited_at")
    var editedAt: Instant? = null

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<Message, UUID>
}

@Entity
@Table(name = "user_presence")
class UserPresenceEntity : PanacheEntityBase {
    @Id
    @Column(name = "user_id")
    lateinit var userId: UUID

    var status: String = "offline"
    var doing: String? = null

    @Column(name = "track_id")
    var trackId: String? = null

    @Column(name = "position_sec")
    var positionSec: Int? = null

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<UserPresenceEntity, UUID>
}

@Entity
@Table(name = "calls")
class CallEntity : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "chat_id")
    lateinit var chatId: UUID

    lateinit var kind: String

    @Column(name = "started_by")
    lateinit var startedBy: UUID

    var status: String = "ringing"

    @Column(name = "started_at")
    var startedAt: Instant = Instant.now()

    @Column(name = "ended_at")
    var endedAt: Instant? = null

    @Column(name = "end_reason")
    var endReason: String? = null

    companion object : PanacheCompanionBase<CallEntity, UUID>
}

@Embeddable
data class CallParticipantId(
    @Column(name = "call_id") var callId: UUID = UUID(0, 0),
    @Column(name = "user_id") var userId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "call_participants")
class CallParticipant : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: CallParticipantId

    var state: String = "invited"

    @Column(name = "joined_at")
    var joinedAt: Instant? = null

    @Column(name = "left_at")
    var leftAt: Instant? = null

    companion object : PanacheCompanionBase<CallParticipant, CallParticipantId>
}

@Entity
@Table(name = "call_signals")
class CallSignalEntity : PanacheEntity() {
    @Column(name = "call_id")
    lateinit var callId: UUID

    @Column(name = "chat_id")
    lateinit var chatId: UUID

    @Column(name = "from_user_id")
    lateinit var fromUserId: UUID

    @Column(name = "to_user_id")
    var toUserId: UUID? = null

    lateinit var kind: String

    @JdbcTypeCode(SqlTypes.JSON)
    lateinit var payload: String

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<CallSignalEntity, Long>
}