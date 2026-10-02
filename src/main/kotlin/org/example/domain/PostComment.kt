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
@Table(name = "post_comment")
class PostComment : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "post_id")
    lateinit var postId: UUID

    @Column(name = "author_id")
    lateinit var authorId: UUID

    @Column(name = "parent_id")
    var parentId: UUID? = null

    lateinit var body: String

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<PostComment, UUID>
}