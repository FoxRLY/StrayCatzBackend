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
@Table(name = "post")
class Post : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "creator_id")
    lateinit var authorId: UUID

    @Column(name = "community_id")
    var communityId: UUID? = null

    @Column(name = "parent_post")
    var parentPost: UUID? = null

    var title: String? = null
    var body: String = ""

    /** text / image / video / track / guide */
    var kind: String = "text"
    var meta: String? = null

    @Column(name = "media_id")
    var mediaId: UUID? = null

    var pinned: Boolean = false

    /** Запись пульса (общая лента). С communityId — ещё и «пульсар» в сообществе. */
    @Column(name = "is_pulse")
    var isPulse: Boolean = false

    /** Опубликовано от имени сообщества (автор — его admin). */
    @Column(name = "as_community")
    var asCommunity: Boolean = false

    /** Запись на стене этого человека (без сообщества, не пульс). */
    @Column(name = "wall_user_id")
    var wallUserId: UUID? = null

    /** Оригинал (для зеркал Telegram: https://t.me/<канал>/<номер>). */
    @Column(name = "source_url")
    var sourceUrl: String? = null

    @Column(name = "post_data", insertable = false, updatable = false)
    var postData: String? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    @Column(name = "is_deleted")
    var isDeleted: Boolean = false

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<Post, UUID>
}