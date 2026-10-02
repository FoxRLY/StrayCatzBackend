package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ нексус (экран входа)

/** Числа на плитках нексуса. */
data class NexusCountsOut(
    val friends: Int,
    /** Друзья не offline — плитка «комнаты». */
    val friendsOnline: Int,
    /** Сообщества, где я участник. */
    val communities: Long,
    /** Новые записи в моих сообществах (участник или читаю) за окно since — «N новых записей за вечер». */
    val freshPosts: Long,
    /** Ролики в моей сети (scope=feed) за последние 7 дней — плитка «видео». */
    val videos: Long,
    /** Друзья, у которых сейчас играет музыка — плитка «музыка». */
    val listening: Int,
    /** Непрочитанные сообщения во всех беседах — плитка «беседы». */
    val unreadMessages: Long,
    val unreadNotifications: Long,
    /** Записи пульса за последние 24 часа. */
    val pulse: Long,
    /** Лента за окно since: записи моих сообществ + записи друзей (пульс, стена). */
    val feed: Long,
    /** Идущие эфиры друзей и моих сообществ. */
    val live: Long,
)

/** Куда ведёт строка активности. type: post / track / community / stream. */
data class NexusTargetOut(val type: String, val id: UUID, val slug: String? = null)

/** Строка «что произошло, пока тебя не было». */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class NexusActivityOut(
    /** post / pulse / wall / track / join / stream */
    val kind: String,
    val who: UserShortOut?,
    /** Готовая фраза после имени: «написал(а) в пульс: …», «выложил(а) трек …». */
    val what: String,
    val target: NexusTargetOut,
    val community: PostCommunityOut?,
    /** Оттенок для аватарки-заглушки: сообщества или стабильный от человека. */
    val hue: Int,
    val at: Instant,
)

data class NexusFriendOut(val user: UserShortOut, val status: String)

data class NexusOut(
    val me: UserShortOut?,
    /** Начало окна «пока тебя не было» (сейчас − 6 часов). */
    val since: Instant,
    val counts: NexusCountsOut,
    val activity: List<NexusActivityOut>,
    /** Друзья онлайн (до 12): online сверху, потом away/dnd. */
    val friendsOnline: List<NexusFriendOut>,
    /** Идущие эфиры друзей и моих сообществ (до 6). */
    val live: List<StreamOut>,
    /** «Горячее» в пульсе за час (до 5). */
    val hot: List<PostOut>,
)
