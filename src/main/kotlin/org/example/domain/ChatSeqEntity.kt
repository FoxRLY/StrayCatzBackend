package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

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