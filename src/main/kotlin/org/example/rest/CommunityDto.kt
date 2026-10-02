package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// Формы ответов подогнаны под фронтовый тип Community из $lib/data.ts:
// slug, name, hue, members, online, about, founded (год строкой), sections
// (подписи разделов), mods, rules (массив строк), fresh, dialect, lexicon.
// Пользователи внутри — как везде: {id, username, avatar, color}.

// ================================================================ сообщество

/** Отношение смотрящего к сообществу. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class MyCommunityOut(
    /** owner / admin / member / null */
    val role: String?,
    val member: Boolean,
    /** «Читать без вступления» */
    val following: Boolean,
)

/** Раздел: ключ для логики + подпись для вкладки. */
data class SectionOut(val key: String, val label: String)

/** Карточка в каталоге (CommunityCard.svelte). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CommunityCardOut(
    val id: UUID,
    val slug: String,
    val name: String,
    val hue: Int,
    val color: String?,
    val avatar: String?,
    val about: String?,
    /** Год основания строкой: "2013". */
    val founded: String,
    /** Подписи разделов, как во фронтовых моках: ["Записи", "Обсуждения", ...]. */
    val sections: List<String>,
    val sectionItems: List<SectionOut>,
    /** Участников (не считая «читающих без вступления»). */
    val members: Long,
    /** Уникальные люди, открывавшие записи сообщества за 30 минут. */
    val online: Long,
    /** Записей за последние сутки. */
    val fresh: Long,
    val followers: Long,
    val dialect: String,
    val lexicon: Map<String, String>,
    /** community / project */
    val kind: String,
    val parentSlug: String?,
    /** Для кнопки на карточке: состоит ли смотрящий. */
    val joined: Boolean,
    val my: MyCommunityOut,
    /** Картинка шапки (верхний бар): /api/media/{id} или внешняя ссылка; null — рисовать по hue. */
    val banner: String? = null,
    /** Какая часть картинки по вертикали в кадре: 0 — верх, 0.5 — центр, 1 — низ (object-position: 50% {focus*100}%). */
    val bannerFocus: Double = 0.5,
    /** telegram — зеркало Telegram-канала (только чтение); null — обычное сообщество. */
    val source: String? = null,
)

/** Кто следит: владелец — «основал», админ — «выбрали». */
data class ModOut(
    val id: UUID,
    val username: String,
    /** = username; поле для совместимости с моками ({name, how}). */
    val name: String,
    val avatar: String?,
    val color: String?,
    val role: String,
    val how: String,
)

/** Страница /c/{slug}: всё из карточки + правила, модераторы, журнал. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CommunityPageOut(
    val id: UUID,
    val slug: String,
    val name: String,
    val hue: Int,
    val color: String?,
    val avatar: String?,
    val about: String?,
    val founded: String,
    val sections: List<String>,
    val sectionItems: List<SectionOut>,
    val members: Long,
    val online: Long,
    val fresh: Long,
    val followers: Long,
    val dialect: String,
    val lexicon: Map<String, String>,
    val kind: String,
    val parentSlug: String?,
    val joined: Boolean,
    val my: MyCommunityOut,
    /** Правила по пункту на строку. */
    val rules: List<String>,
    /** Те же правила одним текстом — для редактора. */
    val rulesText: String?,
    val mods: List<ModOut>,
    /** Журнал модерации — TBD, пока всегда пустой. */
    val modlog: List<Any>,
    val projects: List<CommunityShortOut>,
    val banner: String? = null,
    val bannerFocus: Double = 0.5,
    val tags: List<String> = emptyList(),
    /** Зеркало Telegram-канала: писать нельзя, кнопки «написать/обсуждение/событие» скрыть. */
    val mirror: MirrorOut? = null,
)

data class CommunityListOut(val items: List<CommunityCardOut>, val total: Long)

data class CommunityCreateIn(
    val slug: String? = null,
    val name: String? = null,
    val about: String? = null,
    val hue: Int? = null,
    val color: String? = null,
    val avatar: String? = null,
    /** Ключи или подписи разделов. По умолчанию: записи, обсуждения, события, участники. */
    val sections: List<String>? = null,
    val rules: String? = null,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

/** null / нет поля — не трогаем, "" в тексте — очистить. */
data class CommunityPatchIn(
    val name: String? = null,
    val about: String? = null,
    val hue: Int? = null,
    val color: String? = null,
    val avatar: String? = null,
    /** Правила одним текстом, пункт на строку. */
    val rules: String? = null,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

/** PUT /api/communities/{slug}/avatar (JSON-вариант). */
data class CommunityAvatarIn(val mediaId: UUID? = null)

/** PUT /api/communities/{slug}/banner (JSON-вариант): mediaId — новая картинка, focus — только сдвинуть кадр. */
data class CommunityBannerIn(val mediaId: UUID? = null, val focus: Double? = null)

data class LexiconIn(val dialect: String? = null, val lexicon: Map<String, String>? = null)

data class SectionsIn(val sections: List<String>? = null)

data class LeaveIn(val reason: String? = null)

data class RoleIn(val role: String? = null)

// ================================================================ участники

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CommunityMemberOut(
    val id: UUID,
    val username: String,
    val avatar: String?,
    val color: String?,
    /** online / away / dnd / offline (invisible показывается как offline). */
    val status: String,
    val role: String,
    val reputation: Int,
    val joinedAt: Instant,
)

data class CommunityMembersPageOut(val items: List<CommunityMemberOut>, val total: Long, val online: Long)

// ================================================================ записи

/** Сообщество в записи/видео/эфире/ленте. avatar — чтобы в ленте и пульсе рисовать аватарку сообщества. */
data class PostCommunityOut(val id: UUID, val slug: String, val name: String, val hue: Int, val avatar: String? = null)

/** Что смотрящий уже сделал с записью. */
data class MyPostOut(val upvoted: Boolean, val liked: Boolean, val canComment: Boolean)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PostOut(
    val id: UUID,
    /** slug сообщества — как post.community во фронтовых моках. */
    val community: String?,
    val communityInfo: PostCommunityOut?,
    /** Оттенок сообщества (или 200, если запись вне сообщества). */
    val hue: Int,
    val author: UserShortOut?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val title: String?,
    val body: String,
    /** text / image / video / track / guide */
    val kind: String,
    val meta: String?,
    val mediaUrl: String?,
    val pinned: Boolean,
    /** Апвоуты («стрелочка»). */
    val up: Long,
    val likes: Long,
    val comments: Long,
    val shares: Long,
    /** Читают сейчас: прочтения за последние 5 минут. */
    val readers: Long,
    /** «Температура» 0..1 — заглушка скоринга. */
    val temp: Double,
    /** «Активно обсуждают»: больше 5 комментариев за последнюю минуту. */
    val hot: Boolean,
    val my: MyPostOut,
    /** Запись пульса (общей ленты). */
    val pulse: Boolean,
    /** Запись пульса, привязанная к сообществу, — в сообществе показывается с пометкой «пульсар». */
    val pulsar: Boolean,
    /** Опубликовано от имени сообщества: показывать communityInfo вместо автора. */
    val asCommunity: Boolean,
    /** Картинки/гифки/видео по порядку. */
    val attachments: List<AttachmentOut>,
    val poll: PollOut?,
    /** Прикреплённые треки по порядку. */
    val tracks: List<TrackOut>,
    /** Откуда запись: community / pulse / wall. */
    val source: String = "community",
    /** Для записи на стене — чья это стена (source = wall). */
    val wallOwner: UserShortOut? = null,
    /** Теги: ручные + #хэштеги из текста. Клик — /api/tags/{tag}. */
    val tags: List<String> = emptyList(),
    /** Оригинал во внешнем источнике (записи зеркал Telegram: https://t.me/канал/123). */
    val sourceUrl: String? = null,
)

/** kind: image / gif / video */
data class AttachmentOut(val id: UUID, val url: String, val contentType: String, val kind: String)

data class PollOptionOut(val id: UUID, val text: String, val votes: Long)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PollOut(
    val options: List<PollOptionOut>,
    val multiple: Boolean,
    val closesAt: Instant,
    val closed: Boolean,
    /** Сколько разных людей проголосовало. */
    val voters: Long,
    /** За какие варианты голосовал смотрящий (пусто — не голосовал). */
    val myVotes: List<UUID>,
)

data class PostPageOut(val items: List<PostOut>, val hasMore: Boolean)

data class PostIn(
    val title: String? = null,
    val body: String? = null,
    val kind: String? = null,
    val meta: String? = null,
    /** Устарело: одна картинка. Используй mediaIds. */
    val mediaId: UUID? = null,
    /** До 4 картинок/гифок или одно видео (id из POST /api/media). */
    val mediaIds: List<UUID> = emptyList(),
    /** До 10 треков из своей музыки. */
    val trackIds: List<UUID> = emptyList(),
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

data class UpvoteOut(val up: Long, val upvoted: Boolean, val upvotesLeft: Int)

data class UpvoteBudgetOut(val upvotesLeft: Int, val perDay: Int, val resetsAt: Instant)

data class LikeOut(val likes: Long, val liked: Boolean)

data class ReadOut(val readers: Long)

/**
 * Переслать запись. Куда — любое сочетание (всего до 20 адресатов):
 *  - chatId / chatIds — в чаты, где я состою (личка, группа, обсуждение);
 *  - userId / userIds — людям в личку (личка найдётся или создастся сама).
 */
data class ShareIn(
    val chatId: UUID? = null,
    val comment: String? = null,
    val chatIds: List<UUID> = emptyList(),
    val userId: UUID? = null,
    val userIds: List<UUID> = emptyList(),
)

/** Одна доставка: в какой чат ушло и каким сообщением. userId — если слали человеку в личку. */
data class ShareSentOut(val chatId: UUID, val messageId: UUID, val seq: Long, val userId: UUID? = null)

/** chatId/messageId/seq — первой доставки (как раньше); все доставки — в sent. */
data class ShareOut(
    val chatId: UUID,
    val messageId: UUID,
    val seq: Long,
    val shares: Long,
    val sent: List<ShareSentOut> = emptyList(),
)

/** POST /api/wall — запись на своей стене. Всё как у записи сообщества, без kind/meta. */
data class WallPostIn(
    val title: String? = null,
    val body: String? = null,
    /** До 4 картинок/гифок или одно видео. */
    val mediaIds: List<UUID> = emptyList(),
    val trackIds: List<UUID> = emptyList(),
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

// ================================================================ комментарии

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CommentOut(
    val id: UUID,
    val postId: UUID,
    /** На какой комментарий ответ (null — ответ на запись). Дерево собирает фронт. */
    val parentId: UUID?,
    val author: UserShortOut?,
    /** null у удалённого комментария — ветка ответов под ним сохраняется. */
    val body: String?,
    val deleted: Boolean,
    val createdAt: Instant,
    val canDelete: Boolean,
    /** Картинки/гифки (у удалённого — пусто). */
    val attachments: List<AttachmentOut>,
    val tracks: List<TrackOut>,
)

data class CommentsPageOut(val items: List<CommentOut>, val total: Long, val hasMore: Boolean)

/** body или mediaIds (до 4 картинок/гифок) — хотя бы одно. */
data class CommentIn(
    val body: String? = null,
    val parentId: UUID? = null,
    val mediaIds: List<UUID> = emptyList(),
    val trackIds: List<UUID> = emptyList(),
)

// ================================================================ обсуждения

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DiscussionOut(
    /** Это обычный chatId: история — GET /api/chats/{id}/messages, живое — сокет. */
    val chatId: UUID,
    val title: String,
    val replies: Long,
    val lastAt: Instant?,
    val pinned: Boolean,
    /** Больше 5 сообщений за последнюю минуту. */
    val hot: Boolean,
    val participants: Long,
    val joined: Boolean,
)

data class DiscussionIn(val title: String? = null)

data class PinIn(val pinned: Boolean? = null)

// ================================================================ события

@JsonInclude(JsonInclude.Include.ALWAYS)
data class EventOut(
    val id: UUID,
    val title: String,
    val description: String?,
    val location: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    /** Сколько записалось («84 собираются»). */
    val going: Long,
    val registered: Boolean,
    val cancelled: Boolean,
    val createdBy: UserShortOut?,
    val tags: List<String> = emptyList(),
)

data class EventIn(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

// ================================================================ вики

data class WikiListItemOut(val slug: String, val title: String, val updatedAt: Instant)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WikiPageOut(
    val slug: String,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: UserShortOut?,
)

data class WikiRevisionOut(val id: UUID, val title: String, val body: String, val editor: UserShortOut?, val createdAt: Instant)

data class WikiIn(val title: String? = null, val body: String? = null)

// ================================================================ таблица активности

data class LeaderboardRowOut(
    val rank: Int,
    val user: UserShortOut,
    val score: Long,
    /** Доля от лидера 0..1 — для полоски Meter. */
    val share: Double,
)

// ================================================================ пульс

/**
 * POST /api/pulse. Нужно хотя бы одно: body, mediaIds или poll.
 * communitySlug — привязать к сообществу (станет «пульсаром» там);
 * asCommunity = true — от имени сообщества (нужны права admin).
 */
data class PulseIn(
    val body: String? = null,
    val mediaIds: List<UUID> = emptyList(),
    /** До 10 треков из своей музыки. */
    val trackIds: List<UUID> = emptyList(),
    val poll: PollIn? = null,
    val communitySlug: String? = null,
    val asCommunity: Boolean = false,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

data class PollIn(
    val options: List<String> = emptyList(),
    val multiple: Boolean = false,
    /** Через сколько часов закрыть: 1–168, по умолчанию 24. */
    val closesInHours: Int? = null,
)

data class PollVoteIn(val optionIds: List<UUID> = emptyList())

/**
 * Страница пульса. Для sort=new/friends листать через ?before=nextBefore,
 * для hot/week (порядок по очкам) — через ?offset=nextOffset.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class PulsePageOut(
    val items: List<PostOut>,
    val hasMore: Boolean,
    val sort: String,
    val nextBefore: Instant?,
    val nextOffset: Int?,
)

// ================================================================ теги

data class TagCountOut(val tag: String, val count: Long)

/** GET /api/tags/{tag}: всё с этим тегом, свежее сверху (по 20 каждого вида). */
data class TagPageOut(
    val tag: String,
    /** Сколько всего: {post: 12, video: 3, stream: 1, track: 4, community: 2, event: 1}. */
    val counts: Map<String, Long>,
    val posts: List<PostOut>,
    val videos: List<VideoOut>,
    val streams: List<StreamOut>,
    val tracks: List<TrackOut>,
    val communities: List<PostCommunityOut>,
    val events: List<EventOut>,
)
