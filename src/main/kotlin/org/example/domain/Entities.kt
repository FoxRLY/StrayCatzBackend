package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.io.Serializable
import java.time.Instant
import java.util.UUID

// Маппинг 1:1 на V1__init.sql (+ V2__ws_support.sql). Таблицы, которые
// сокету не нужны (post, community*, user_cosmetics, user_level), не маплю.
// friendship без PK — читаю её нативным SQL (см. PresenceService).

@Entity
@Table(name = "users")
class AppUser : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(nullable = false, unique = true)
    lateinit var username: String

    @Column(name = "is_deleted")
    var isDeleted: Boolean = false

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<AppUser, UUID>
}

@Entity
@Table(name = "user_cosmetics")
class UserCosmetics : PanacheEntityBase {
    @Id
    @Column(name = "user_id")
    lateinit var userId: UUID

    var avatar: String? = null
    var color: String? = null
    var tagline: String? = null

    companion object : PanacheCompanionBase<UserCosmetics, UUID>
}

@Entity
@Table(name = "user_level")
class UserLevel : PanacheEntityBase {
    @Id
    @Column(name = "user_id")
    lateinit var userId: UUID

    var xp: Int = 0
    var level: Int = 0

    companion object : PanacheCompanionBase<UserLevel, UUID>
}

@Entity
@Table(name = "chat")
class Chat : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    var name: String? = null

    /** 'direct' / 'group' / ... — значения на твоё усмотрение, сокет их просто отдаёт клиенту. */
    @Column(name = "room_type", nullable = false)
    lateinit var roomType: String

    /** Только для 'direct': "<меньший uuid>:<больший uuid>", уникален (V3). */
    @Column(name = "direct_key")
    var directKey: String? = null

    // json-колонки сокет не меняет — читаем как текст, в UPDATE не включаем
    @Column(name = "permission_rules", insertable = false, updatable = false)
    var permissionRules: String? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "is_deleted")
    var isDeleted: Boolean = false

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<Chat, UUID>
}

@Embeddable
data class ChatMemberId(
    @Column(name = "chat_id") var chatId: UUID = UUID(0, 0),
    @Column(name = "user_id") var userId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "chat_member")
class ChatMember : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: ChatMemberId

    @Column(insertable = false, updatable = false)
    var permissions: String? = null

    @Column(name = "is_deleted")
    var isDeleted: Boolean = false

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

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
@Table(name = "message")
class Message : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "chat_id")
    lateinit var chatId: UUID

    var seq: Long = 0

    /** В твоей схеме автор — user_id (было author_id). */
    @Column(name = "user_id")
    lateinit var userId: UUID

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

// ---- звонки (таблицы из V2) ----

@Entity
@Table(name = "call")
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
@Table(name = "call_participant")
class CallParticipant : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: CallParticipantId

    /** invited / accepted / declined / missed / left */
    var state: String = "invited"

    @Column(name = "joined_at")
    var joinedAt: Instant? = null

    @Column(name = "left_at")
    var leftAt: Instant? = null

    companion object : PanacheCompanionBase<CallParticipant, CallParticipantId>
}

@Entity
@Table(name = "call_signal")
class CallSignalEntity : PanacheEntityBase {
    // bigserial в БД -> IDENTITY. (PanacheEntity() тут не подходит: он
    // ждёт свою sequence call_signal_SEQ, которой в схеме нет.)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "call_id")
    lateinit var callId: UUID

    @Column(name = "chat_id")
    lateinit var chatId: UUID

    @Column(name = "from_user_id")
    lateinit var fromUserId: UUID

    @Column(name = "to_user_id")
    var toUserId: UUID? = null

    lateinit var kind: String

    /** jsonb: пишем строкой с явным кастом — без магии FormatMapper'а Hibernate. */
    @ColumnTransformer(write = "?::jsonb")
    lateinit var payload: String

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<CallSignalEntity, Long>
}
