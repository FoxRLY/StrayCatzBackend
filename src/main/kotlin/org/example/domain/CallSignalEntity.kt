package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant
import java.util.UUID

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
