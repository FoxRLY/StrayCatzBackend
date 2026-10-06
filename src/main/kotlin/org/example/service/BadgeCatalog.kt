package org.example.service

/**
 * Значок: получается сам, когда метрика [metric] доходит до [goal].
 * За значок начисляется [xp] опыта.
 */
data class BadgeDef(
    val code: String,
    val title: String,
    val description: String,
    /** Эмодзи-иконка; фронт может заменить своей картинкой по code. */
    val icon: String,
    val xp: Int,
    val metric: String,
    val goal: Long,
    /** Как подписать прогресс: «47 / 100 ч». */
    val unit: String = "",
)

/**
 * Каталог значков. Только количественные условия — каждое считается одним
 * запросом (см. BadgeService.METRICS). Новый значок = новая строка здесь;
 * если метрика новая — ещё запрос в METRICS. Код значка не меняется никогда:
 * он лежит в user_badge.
 */
object BadgeCatalog {
    val ALL: List<BadgeDef> = listOf(
        BadgeDef("night_watch", "Ночной сторож", "100 часов в сети после полуночи", "🌙", 100, "night_hours", 100, "ч"),
        BadgeDef("listened_together", "Слушали вместе", "один трек одновременно с другом", "🎧", 20, "together", 1),
        BadgeDef("guestbook", "Гостевая", "50 записей от других людей", "📖", 50, "guestbook_from_others", 50),
        BadgeDef("handmade", "Своими руками", "комната собрана без готовых шаблонов: своя картинка на стене, «обо мне» и настроение", "🛠️", 30, "room_handmade", 1),
        BadgeDef("yard_keeper", "Держит двор", "1000 участников в своём сообществе", "🏘️", 200, "community_members", 1000),
        BadgeDef("thousand_tracks", "Тысяча треков", "прослушать 1000 разных треков", "💿", 100, "tracks_listened", 1000),
        BadgeDef("first_post", "Первая запись", "написать первую запись", "✏️", 10, "posts", 1),
        BadgeDef("chronicler", "Летописец", "100 записей", "📜", 100, "posts", 100),
        BadgeDef("commentator", "Комментатор", "100 комментариев", "💬", 50, "comments", 100),
        BadgeDef("people_choice", "Народная любовь", "100 апвоутов на твои записи", "⬆️", 100, "upvotes_received", 100),
        BadgeDef("company", "Своя компания", "20 друзей", "🤝", 30, "friends", 20),
        BadgeDef("musician", "Музыкант", "загрузить 10 своих треков", "🎸", 50, "tracks_uploaded", 10),
        BadgeDef("trader", "Барыга", "продать вещь на барахолке", "🏷️", 20, "market_sold", 1),
    )

    val BY_CODE: Map<String, BadgeDef> = ALL.associateBy { it.code }
}
