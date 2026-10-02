package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

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