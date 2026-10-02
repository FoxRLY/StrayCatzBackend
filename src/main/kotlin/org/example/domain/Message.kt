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

    /** Репост записи сообщества в чат (кнопка «поделиться»). */
    @Column(name = "shared_post_id")
    var sharedPostId: UUID? = null

    /** Ответ на сообщение этого же чата. */
    @Column(name = "reply_to_id")
    var replyToId: UUID? = null

    /** Пересланное: автор оригинала, сам оригинал, откуда и когда он написан. */
    @Column(name = "fwd_user_id")
    var fwdUserId: UUID? = null

    @Column(name = "fwd_message_id")
    var fwdMessageId: UUID? = null

    @Column(name = "fwd_chat_id")
    var fwdChatId: UUID? = null

    @Column(name = "fwd_at")
    var fwdAt: Instant? = null

    /** Гифка или внешний стикер (таблица gif). */
    @Column(name = "gif_id")
    var gifId: UUID? = null

    /** Стикер из набора (таблица sticker). */
    @Column(name = "sticker_id")
    var stickerId: UUID? = null

    companion object : PanacheCompanionBase<Message, UUID>
}