package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ треки

@JsonInclude(JsonInclude.Include.ALWAYS)
data class TrackOut(
    val id: UUID,
    val title: String,
    val artist: String,
    val album: String?,
    /** Длительность в секундах (приходит от фронта при загрузке). */
    val durationSec: Int,
    /** Темп — заполняет фронт (PATCH), может быть null. */
    val bpm: Int?,
    val tags: List<String>,
    /** Оттенок 0..359 для обложки-заглушки (стабилен для трека). */
    val hue: Int,
    /** /api/media/{id} — ставить в <audio src> (редирект в S3, перемотка работает). */
    val audioUrl: String,
    val coverUrl: String?,
    val uploader: UserShortOut?,
    val plays: Long,
    val createdAt: Instant,
    /** Трек есть в «моей музыке» смотрящего (в кадрах сокета всегда false). */
    val inLibrary: Boolean,
    /** Смотрящий его загрузил — можно править и удалять. */
    val mine: Boolean,
)

data class TrackPageOut(val items: List<TrackOut>, val total: Long, val hasMore: Boolean)

/**
 * POST /api/music/tracks. Сначала файл в POST /api/media (mp3/m4a/ogg/flac/wav до 30 МБ),
 * обложка — туда же картинкой. durationSec фронт берёт из <audio>.duration.
 */
data class TrackIn(
    val audioMediaId: UUID? = null,
    val coverMediaId: UUID? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationSec: Int? = null,
    val bpm: Int? = null,
    val tags: List<String>? = null,
    /** Сразу добавить в музыку сообщества (нужно участие). */
    val communitySlug: String? = null,
)

/** null / нет поля — не трогаем; "" в album — очистить; clearCover = true — убрать обложку. */
data class TrackPatchIn(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val bpm: Int? = null,
    val tags: List<String>? = null,
    val coverMediaId: UUID? = null,
    val clearCover: Boolean = false,
)

// ================================================================ плейлисты

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PlaylistOut(
    val id: UUID,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val isPublic: Boolean,
    val owner: UserShortOut?,
    /** Если плейлист сообщества. */
    val community: PostCommunityOut?,
    val trackCount: Long,
    val durationSec: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Может ли смотрящий править (владелец или admin сообщества). */
    val canEdit: Boolean,
    /** Треки по порядку — только в GET /api/music/playlists/{id}, в списках null. */
    val tracks: List<TrackOut>?,
)

data class PlaylistIn(
    val title: String? = null,
    val description: String? = null,
    val coverMediaId: UUID? = null,
    val isPublic: Boolean = true,
    /** Плейлист сообщества (нужен admin). */
    val communitySlug: String? = null,
    /** Сразу положить треки. */
    val trackIds: List<UUID> = emptyList(),
)

data class PlaylistPatchIn(
    val title: String? = null,
    val description: String? = null,
    val coverMediaId: UUID? = null,
    val clearCover: Boolean = false,
    val isPublic: Boolean? = null,
)

data class TrackOrderIn(val trackIds: List<UUID> = emptyList())

// ================================================================ сейчас слушает

data class NowPlayingIn(val trackId: UUID? = null, val positionSec: Int = 0)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class NowPlayingOut(
    val userId: UUID,
    /** false — ничего не играет (не включал, выключил или трек закончился). */
    val playing: Boolean,
    val track: TrackOut?,
    val startedAt: Instant?,
    /** Когда трек закончится, если его не переключить. */
    val endsAt: Instant?,
    /** Где сейчас позиция (по серверным часам) — для полоски прогресса. */
    val positionSec: Int?,
)

data class FriendMusicOut(val user: UserShortOut, val trackCount: Long, val nowPlaying: NowPlayingOut)
