package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

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

    /** Для обсуждений (room_type 'community'): чьё это обсуждение. */
    @Column(name = "community_id")
    var communityId: UUID? = null

    var pinned: Boolean = false

    @Column(name = "created_by")
    var createdBy: UUID? = null

    /** Аватар беседы (/api/media/{id} или ссылка). У лички не используется — там аватар собеседника. */
    var avatar: String? = null

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