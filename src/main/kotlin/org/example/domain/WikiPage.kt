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
@Table(name = "wiki_page")
class WikiPage : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "community_id")
    lateinit var communityId: UUID

    lateinit var slug: String
    lateinit var title: String
    var body: String = ""

    @Column(name = "created_by")
    lateinit var createdBy: UUID

    @Column(name = "updated_by")
    lateinit var updatedBy: UUID

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<WikiPage, UUID>
}