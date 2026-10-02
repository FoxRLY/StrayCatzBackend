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
@Table(name = "wiki_revision")
class WikiRevision : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "page_id")
    lateinit var pageId: UUID

    lateinit var title: String
    lateinit var body: String

    @Column(name = "editor_id")
    lateinit var editorId: UUID

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<WikiRevision, UUID>
}
