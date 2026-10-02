package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "community")
class Community : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    lateinit var name: String
    lateinit var slug: String
    var description: String? = null
    var color: String? = null
    var avatar: String? = null

    @Column(name = "owner_id")
    var ownerId: UUID? = null

    /** Оттенок 0..359 — фронт красит баннер через hsl(var(--h) ...). */
    var hue: Int = 200

    /** Шапка (верхний бар): адрес картинки. */
    var banner: String? = null

    /** Зеркало внешнего источника ('telegram') — писать сюда нельзя. null — обычное сообщество. */
    var source: String? = null

    /** Username канала-источника. */
    @Column(name = "source_ref")
    var sourceRef: String? = null

    /** 0..1 — какая часть шапки по вертикали в кадре. */
    @Column(name = "banner_focus")
    var bannerFocus: Double = 0.5

    /** Правила одним текстом, пункт на строку. */
    @Column(name = "rules_text")
    var rulesText: String? = null

    var dialect: String = "default"

    /** JSON {ключ: слово} — свои названия кнопок и счётчиков. */
    @ColumnTransformer(write = "?::jsonb")
    var lexicon: String = "{}"

    /** JSON-массив ключей разделов в порядке показа. */
    @ColumnTransformer(write = "?::jsonb")
    var sections: String = "[]"

    /** community / project (подсообщество с урезанными разделами). */
    var kind: String = "community"

    @Column(name = "parent_id")
    var parentId: UUID? = null

    @Column(name = "permission_rules", insertable = false, updatable = false)
    var permissionRules: String? = null

    @Column(name = "is_deleted")
    var isDeleted: Boolean = false

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<Community, UUID>
}