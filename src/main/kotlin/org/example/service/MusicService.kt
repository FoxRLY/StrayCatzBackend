package org.example.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.domain.Community
import org.example.domain.Playlist
import org.example.domain.Track
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.rest.ApiException
import org.example.rest.FriendMusicOut
import org.example.rest.NowPlayingOut
import org.example.rest.PlaylistIn
import org.example.rest.PlaylistOut
import org.example.rest.PlaylistPatchIn
import org.example.rest.PostCommunityOut
import org.example.rest.TrackIn
import org.example.rest.TrackOut
import org.example.rest.TrackPageOut
import org.example.rest.TrackPatchIn
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Музыка «как в ВК»:
 *  - трек загружает человек: файл (POST /api/media) + исполнитель, название, альбом, обложка;
 *  - «моя музыка» — загруженное и добавленное себе (user_track);
 *  - у друзей и сообществ — своя музыка; поиск по названию и исполнителю по всем трекам;
 *  - плейлисты — личные и сообществ;
 *  - «сейчас слушает»: трек считается играющим до started_at + длительность,
 *    пока не включили другой; дальше — ничего не играет.
 */
@ApplicationScoped
class MusicService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val media: MediaService,
    private val profiles: UserProfileService,
    private val friends: FriendService,
    private val communities: CommunityService,
    private val tagLinks: TagService,
) {
    companion object {
        const val MAX_TEXT = 200
        const val MAX_TAGS = 5
        const val MAX_TAG = 30
        const val MAX_PAGE = 100
        const val MAX_ATTACHED_TRACKS = 10
        const val MAX_PLAYLIST_TRACKS = 1000
        private val STRING_LIST = object : TypeReference<List<String>>() {}
    }

    // ================================================================ треки

    @Transactional
    fun upload(me: UUID, req: TrackIn): TrackOut {
        val audioId = req.audioMediaId ?: throw bad("invalid_audio", "нужен audioMediaId (загрузи файл в POST /api/media)")
        val audio = media.requireOwned(audioId, me)
        if (!audio.contentType.startsWith("audio/")) throw bad("invalid_audio", "audioMediaId должен быть аудиофайлом")
        req.coverMediaId?.let { requireImage(it, me) }
        val duration = req.durationSec ?: throw bad("invalid_duration", "нужен durationSec (из <audio>.duration)")
        if (duration !in 1..7200) throw bad("invalid_duration", "durationSec: 1–7200")
        val community = req.communitySlug?.takeIf { it.isNotBlank() }?.let { slug ->
            communities.bySlug(slug).also { communities.requireRole(it, me, "member") }
        }

        val t = Track().also {
            it.id = UUID.randomUUID()
            it.uploaderId = me
            it.audioMediaId = audioId
            it.coverMediaId = req.coverMediaId
            it.title = text(req.title, "title") ?: throw bad("invalid_title", "нужно название")
            it.artist = text(req.artist, "artist") ?: throw bad("invalid_artist", "нужен исполнитель")
            it.album = text(req.album, "album")
            it.durationSec = duration
            it.bpm = bpm(req.bpm)
            it.tags = mapper.writeValueAsString(tags(req.tags))
        }
        t.persist()
        syncTags(t, req.tags)
        exec("insert into user_track (user_id, track_id) values (?1, ?2) on conflict do nothing", me, t.id)
        community?.let {
            exec(
                "insert into community_track (community_id, track_id, added_by) values (?1, ?2, ?3) on conflict do nothing",
                it.id, t.id, me,
            )
        }
        return render(listOf(t), me).first()
    }

    @Transactional
    fun get(id: UUID, me: UUID): TrackOut = render(listOf(activeTrack(id)), me).first()

    /** Править может только загрузивший. */
    @Transactional
    fun update(me: UUID, id: UUID, req: TrackPatchIn): TrackOut {
        val t = ownTrack(me, id)
        req.title?.let { t.title = text(it, "title") ?: throw bad("invalid_title", "название не может быть пустым") }
        req.artist?.let { t.artist = text(it, "artist") ?: throw bad("invalid_artist", "исполнитель не может быть пустым") }
        req.album?.let { t.album = text(it, "album") }
        req.bpm?.let { t.bpm = bpm(it) }
        req.tags?.let { t.tags = mapper.writeValueAsString(tags(it)) }
        if (req.tags != null || req.title != null) syncTags(t, req.tags ?: runCatching { mapper.readValue(t.tags, STRING_LIST) }.getOrDefault(emptyList()))
        if (req.clearCover) t.coverMediaId = null
        req.coverMediaId?.let { requireImage(it, me); t.coverMediaId = it }
        t.updatedAt = Instant.now()
        return render(listOf(t), me).first()
    }

    /**
     * Теги трека в общий поиск по тегам: «drum and bass» → drum_and_bass,
     * плюс #хэштеги из названия. Сами теги трека (tags в TrackOut) остаются как ввёл человек.
     */
    private fun syncTags(t: Track, raw: List<String>?) {
        tagLinks.sync(TagService.Owner.TRACK, t.id, raw.orEmpty().mapNotNull { TagService.normalize(it) }.distinct(), t.title)
    }

    /** Удаляет загрузивший: трек пропадает отовсюду (библиотеки, плейлисты, вложения показывают без него). */
    @Transactional
    fun delete(me: UUID, id: UUID) {
        ownTrack(me, id).deletedAt = Instant.now()
        exec("delete from now_playing where track_id = ?1", id)
    }

    /** Поиск по названию и исполнителю по всем трекам. Популярные выше. */
    @Transactional
    fun search(me: UUID, query: String?, limit: Int, offset: Int): TrackPageOut {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return TrackPageOut(emptyList(), 0, false)
        val like = like(q)
        return page(
            me,
            "from track t where t.deleted_at is null and (lower(t.title) like ?1 or lower(t.artist) like ?1 or lower(coalesce(t.album, '')) like ?1)",
            "order by t.plays desc, t.created_at desc",
            listOf(like), limit, offset,
        )
    }

    /** «Моя музыка» или музыка другого человека. ?q — фильтр внутри. Свежедобавленные сверху. */
    @Transactional
    fun library(me: UUID, ownerId: UUID, query: String?, limit: Int, offset: Int): TrackPageOut {
        val like = like(query?.trim().orEmpty())
        return page(
            me,
            """from user_track ut join track t on t.id = ut.track_id
               where ut.user_id = ?1 and t.deleted_at is null
                 and (?2 = '%%' or lower(t.title) like ?2 or lower(t.artist) like ?2)""",
            "order by ut.added_at desc",
            listOf(ownerId, like), limit, offset,
        )
    }

    @Transactional
    fun addToLibrary(me: UUID, trackId: UUID): TrackOut {
        val t = activeTrack(trackId)
        exec("insert into user_track (user_id, track_id) values (?1, ?2) on conflict do nothing", me, t.id)
        return render(listOf(t), me).first()
    }

    @Transactional
    fun removeFromLibrary(me: UUID, trackId: UUID) {
        exec("delete from user_track where user_id = ?1 and track_id = ?2", me, trackId)
    }

    /** Музыка друзей: у кого сколько треков и что играет сейчас. */
    @Transactional
    fun friendsMusic(me: UUID): List<FriendMusicOut> {
        val ids = friends.friendIdsOf(me)
        if (ids.isEmpty()) return emptyList()
        val users = profiles.shorts(ids)
        val counts = counts(
            "select ut.user_id, count(*) from user_track ut join track t on t.id = ut.track_id " +
                    "where t.deleted_at is null and ut.user_id in (?1) group by ut.user_id",
            ids,
        )
        val playing = nowPlayingMany(ids, me)
        return ids.mapNotNull { id ->
            users[id]?.let { FriendMusicOut(it, counts[id] ?: 0, playing.getValue(id)) }
        }.sortedWith(compareByDescending<FriendMusicOut> { it.nowPlaying.playing }.thenBy { it.user.username })
    }

    // ================================================================ сообщество

    @Transactional
    fun communityTracks(slug: String, me: UUID, query: String?, limit: Int, offset: Int): TrackPageOut {
        val c = communities.bySlug(slug)
        val like = like(query?.trim().orEmpty())
        return page(
            me,
            """from community_track ct join track t on t.id = ct.track_id
               where ct.community_id = ?1 and t.deleted_at is null
                 and (?2 = '%%' or lower(t.title) like ?2 or lower(t.artist) like ?2)""",
            "order by ct.added_at desc",
            listOf(c.id, like), limit, offset,
        )
    }

    /** Добавить трек в музыку сообщества — любой участник. */
    @Transactional
    fun addToCommunity(me: UUID, slug: String, trackId: UUID): TrackOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "member")
        val t = activeTrack(trackId)
        exec(
            "insert into community_track (community_id, track_id, added_by) values (?1, ?2, ?3) on conflict do nothing",
            c.id, t.id, me,
        )
        return render(listOf(t), me).first()
    }

    /** Убрать: кто добавил или admin. */
    @Transactional
    fun removeFromCommunity(me: UUID, slug: String, trackId: UUID) {
        val c = communities.bySlug(slug)
        @Suppress("UNCHECKED_CAST")
        val addedBy = (em.createNativeQuery(
            "select added_by from community_track where community_id = ?1 and track_id = ?2", UUID::class.java,
        ).setParameter(1, c.id).setParameter(2, trackId).resultList as List<UUID>).firstOrNull() ?: return
        if (addedBy != me) communities.requireRole(c, me, "admin")
        exec("delete from community_track where community_id = ?1 and track_id = ?2", c.id, trackId)
    }

    // ================================================================ плейлисты

    @Transactional
    fun myPlaylists(me: UUID): List<PlaylistOut> =
        renderPlaylists(Playlist.list("ownerId = ?1 and communityId is null and deletedAt is null order by updatedAt desc", me), me)

    /** Для ленты: плейлисты пачкой без треков (публичные или свои; удалённые пропускаются). */
    @Transactional
    fun playlistsByIds(ids: Collection<UUID>, me: UUID): Map<UUID, PlaylistOut> {
        if (ids.isEmpty()) return emptyMap()
        val list = Playlist.list("id in ?1 and deletedAt is null and (isPublic = true or ownerId = ?2)", ids.distinct(), me)
        return renderPlaylists(list, me).associateBy { it.id }
    }

    /** Чужие — только публичные. */
    @Transactional
    fun userPlaylists(userId: UUID, me: UUID): List<PlaylistOut> {
        val q = if (userId == me) "ownerId = ?1 and communityId is null and deletedAt is null order by updatedAt desc"
        else "ownerId = ?1 and communityId is null and deletedAt is null and isPublic = true order by updatedAt desc"
        return renderPlaylists(Playlist.list(q, userId), me)
    }

    @Transactional
    fun communityPlaylists(slug: String, me: UUID): List<PlaylistOut> {
        val c = communities.bySlug(slug)
        val member = communities.roleOf(c.id, me) != null
        val q = if (member) "communityId = ?1 and deletedAt is null order by updatedAt desc"
        else "communityId = ?1 and deletedAt is null and isPublic = true order by updatedAt desc"
        return renderPlaylists(Playlist.list(q, c.id), me)
    }

    @Transactional
    fun createPlaylist(me: UUID, req: PlaylistIn): PlaylistOut {
        val community = req.communitySlug?.takeIf { it.isNotBlank() }?.let { slug ->
            communities.bySlug(slug).also { communities.requireRole(it, me, "admin") }
        }
        req.coverMediaId?.let { requireImage(it, me) }
        val p = Playlist().also {
            it.id = UUID.randomUUID()
            it.ownerId = me
            it.communityId = community?.id
            it.title = text(req.title, "title") ?: throw bad("invalid_title", "нужно название")
            it.description = longText(req.description)
            it.coverMediaId = req.coverMediaId
            it.isPublic = req.isPublic
        }
        p.persist()
        req.trackIds.distinct().forEachIndexed { i, tid ->
            activeTrack(tid)
            exec("insert into playlist_track (playlist_id, track_id, position) values (?1, ?2, ?3) on conflict do nothing", p.id, tid, i)
        }
        return playlist(p.id, me)
    }

    /** Плейлист с треками. Приватный видит только владелец (или участники сообщества). */
    @Transactional
    fun playlist(id: UUID, me: UUID): PlaylistOut {
        val p = visiblePlaylist(id, me)
        @Suppress("UNCHECKED_CAST")
        val ids = em.createNativeQuery(
            """
            select pt.track_id from playlist_track pt join track t on t.id = pt.track_id
            where pt.playlist_id = ?1 and t.deleted_at is null order by pt.position, pt.added_at
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, p.id).resultList as List<UUID>
        return renderPlaylists(listOf(p), me, withTracks = renderByIds(ids, me)).first()
    }

    @Transactional
    fun updatePlaylist(me: UUID, id: UUID, req: PlaylistPatchIn): PlaylistOut {
        val p = editablePlaylist(me, id)
        req.title?.let { p.title = text(it, "title") ?: throw bad("invalid_title", "название не может быть пустым") }
        req.description?.let { p.description = longText(it) }
        req.isPublic?.let { p.isPublic = it }
        if (req.clearCover) p.coverMediaId = null
        req.coverMediaId?.let { requireImage(it, me); p.coverMediaId = it }
        p.updatedAt = Instant.now()
        return playlist(p.id, me)
    }

    @Transactional
    fun deletePlaylist(me: UUID, id: UUID) {
        editablePlaylist(me, id).deletedAt = Instant.now()
    }

    /** Добавить трек в конец. */
    @Transactional
    fun addToPlaylist(me: UUID, id: UUID, trackId: UUID): PlaylistOut {
        val p = editablePlaylist(me, id)
        activeTrack(trackId)
        val count = count("select count(*) from playlist_track where playlist_id = ?1", p.id)
        if (count >= MAX_PLAYLIST_TRACKS) throw bad("playlist_full", "в плейлисте не больше $MAX_PLAYLIST_TRACKS треков")
        exec(
            """
            insert into playlist_track (playlist_id, track_id, position)
            values (?1, ?2, coalesce((select max(position) + 1 from playlist_track where playlist_id = ?1), 0))
            on conflict do nothing
            """.trimIndent(),
            p.id, trackId,
        )
        p.updatedAt = Instant.now()
        return playlist(p.id, me)
    }

    @Transactional
    fun removeFromPlaylist(me: UUID, id: UUID, trackId: UUID): PlaylistOut {
        val p = editablePlaylist(me, id)
        exec("delete from playlist_track where playlist_id = ?1 and track_id = ?2", p.id, trackId)
        p.updatedAt = Instant.now()
        return playlist(p.id, me)
    }

    /** Новый порядок: trackIds — все треки плейлиста ровно по разу. */
    @Transactional
    fun reorderPlaylist(me: UUID, id: UUID, trackIds: List<UUID>): PlaylistOut {
        val p = editablePlaylist(me, id)
        @Suppress("UNCHECKED_CAST")
        val current = (em.createNativeQuery("select track_id from playlist_track where playlist_id = ?1", UUID::class.java)
            .setParameter(1, p.id).resultList as List<UUID>).toSet()
        if (trackIds.toSet() != current || trackIds.size != current.size) {
            throw bad("invalid_order", "trackIds должны содержать все треки плейлиста ровно по разу")
        }
        trackIds.forEachIndexed { i, tid ->
            exec("update playlist_track set position = ?3 where playlist_id = ?1 and track_id = ?2", p.id, tid, i)
        }
        p.updatedAt = Instant.now()
        return playlist(p.id, me)
    }

    // ================================================================ сейчас слушает

    /**
     * «Включил трек». Играет с позиции positionSec до конца трека; включил
     * другой — заменяется; кончилось время — «ничего не играет». Друзьям
     * уходит кадр music.now_playing.
     */
    @Transactional
    fun startPlaying(me: UUID, trackId: UUID, positionSec: Int): NowPlayingOut {
        val t = activeTrack(trackId)
        val pos = positionSec.coerceIn(0, (t.durationSec - 1).coerceAtLeast(0))
        val startedAt = Instant.now().minusSeconds(pos.toLong())
        val endsAt = startedAt.plusSeconds(t.durationSec.toLong())
        exec(
            """
            insert into now_playing (user_id, track_id, started_at, ends_at) values (?1, ?2, ?3, ?4)
            on conflict (user_id) do update set track_id = excluded.track_id, started_at = excluded.started_at, ends_at = excluded.ends_at
            """.trimIndent(),
            me, t.id, startedAt, endsAt,
        )
        // прослушивание считаем, только если начали с начала — перемотка не накручивает
        if (pos < 5) exec("update track set plays = plays + 1 where id = ?1", t.id)
        val out = NowPlayingOut(me, true, render(listOf(t), me).first(), startedAt, endsAt, pos)
        pushToFriends(me, out.copy(track = out.track?.copy(inLibrary = false, mine = false)))
        return out
    }

    /** Пауза/стоп — «ничего не играет». */
    @Transactional
    fun stopPlaying(me: UUID): NowPlayingOut {
        exec("delete from now_playing where user_id = ?1", me)
        val out = NowPlayingOut(me, false, null, null, null, null)
        pushToFriends(me, out)
        return out
    }

    /** Что слушает человек. Видно самому и друзьям, остальным — playing=false. */
    @Transactional
    fun nowPlaying(userId: UUID, me: UUID): NowPlayingOut {
        if (userId != me && userId !in friends.friendIdsOf(me)) return NowPlayingOut(userId, false, null, null, null, null)
        return nowPlayingMany(listOf(userId), me).getValue(userId)
    }

    /** Друзья, которые слушают что-то прямо сейчас. */
    @Transactional
    fun friendsListening(me: UUID): List<NowPlayingOut> {
        val ids = friends.friendIdsOf(me)
        return nowPlayingMany(ids, me).values.filter { it.playing }.sortedByDescending { it.startedAt }
    }

    // ================================================================ для вложений

    /** Прикрепить можно свои треки: загруженные или добавленные в «мою музыку». */
    @Transactional
    fun validateAttachable(me: UUID, trackIds: List<UUID>?): List<UUID> {
        val ids = trackIds.orEmpty().distinct()
        if (ids.isEmpty()) return emptyList()
        if (ids.size > MAX_ATTACHED_TRACKS) throw bad("too_many_tracks", "не больше $MAX_ATTACHED_TRACKS треков")
        @Suppress("UNCHECKED_CAST")
        val ok = (em.createNativeQuery(
            """
            select t.id from track t where t.id in (?1) and t.deleted_at is null
              and (t.uploader_id = ?2 or exists (select 1 from user_track ut where ut.track_id = t.id and ut.user_id = ?2))
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, ids).setParameter(2, me).resultList as List<UUID>).toSet()
        val missing = ids.filter { it !in ok }
        if (missing.isNotEmpty()) throw ApiException.forbidden("прикрепить можно только треки из своей музыки: $missing")
        return ids
    }

    /** Треки по id в том же порядке (удалённые пропускаются). me = null — без inLibrary/mine. */
    @Transactional
    fun renderByIds(ids: List<UUID>, me: UUID?): List<TrackOut> {
        if (ids.isEmpty()) return emptyList()
        val byId = Track.list("id in ?1 and deletedAt is null", ids.distinct()).associateBy { it.id }
        return render(ids.mapNotNull { byId[it] }, me)
    }

    // ================================================================ utils

    private fun render(tracks: List<Track>, me: UUID?): List<TrackOut> {
        if (tracks.isEmpty()) return emptyList()
        val uploaders = profiles.shorts(tracks.map { it.uploaderId })
        val inLib = if (me == null) emptySet() else {
            @Suppress("UNCHECKED_CAST")
            (em.createNativeQuery("select track_id from user_track where user_id = ?1 and track_id in (?2)", UUID::class.java)
                .setParameter(1, me).setParameter(2, tracks.map { it.id }).resultList as List<UUID>).toSet()
        }
        return tracks.map { t ->
            TrackOut(
                id = t.id,
                title = t.title,
                artist = t.artist,
                album = t.album,
                durationSec = t.durationSec,
                bpm = t.bpm,
                tags = runCatching { mapper.readValue(t.tags, STRING_LIST) }.getOrDefault(emptyList()),
                hue = Math.floorMod(t.id.hashCode(), 360),
                audioUrl = media.url(t.audioMediaId),
                coverUrl = t.coverMediaId?.let { media.url(it) },
                uploader = uploaders[t.uploaderId],
                plays = t.plays,
                createdAt = t.createdAt,
                inLibrary = t.id in inLib,
                mine = me != null && t.uploaderId == me,
            )
        }
    }

    /** select t.id + [fromWhere] + [orderBy], с total и hasMore. */
    @Suppress("UNCHECKED_CAST")
    private fun page(me: UUID, fromWhere: String, orderBy: String, params: List<Any>, limit: Int, offset: Int): TrackPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val from = offset.coerceAtLeast(0)
        val countQ = em.createNativeQuery("select count(*) $fromWhere")
        val idsQ = em.createNativeQuery("select t.id $fromWhere $orderBy limit ${size + 1} offset $from", UUID::class.java)
        params.forEachIndexed { i, p -> countQ.setParameter(i + 1, p); idsQ.setParameter(i + 1, p) }
        val total = (countQ.singleResult as Number).toLong()
        val ids = idsQ.resultList as List<UUID>
        return TrackPageOut(renderByIds(ids.take(size), me), total, ids.size > size)
    }

    private fun renderPlaylists(list: List<Playlist>, me: UUID, withTracks: List<TrackOut>? = null): List<PlaylistOut> {
        if (list.isEmpty()) return emptyList()
        val ids = list.map { it.id }
        @Suppress("UNCHECKED_CAST")
        val stats = (em.createNativeQuery(
            """
            select pt.playlist_id, count(*), coalesce(sum(t.duration_sec), 0)
            from playlist_track pt join track t on t.id = pt.track_id and t.deleted_at is null
            where pt.playlist_id in (?1) group by pt.playlist_id
            """.trimIndent(),
        ).setParameter(1, ids).resultList as List<Array<Any?>>).associateBy { it[0] as UUID }
        val owners = profiles.shorts(list.map { it.ownerId })
        val comms = list.mapNotNull { it.communityId }.distinct().let { c ->
            if (c.isEmpty()) emptyMap() else Community.list("id in ?1", c).associateBy { it.id }
        }
        return list.map { p ->
            val s = stats[p.id]
            val c = p.communityId?.let { comms[it] }
            PlaylistOut(
                id = p.id,
                title = p.title,
                description = p.description,
                coverUrl = p.coverMediaId?.let { media.url(it) },
                isPublic = p.isPublic,
                owner = owners[p.ownerId],
                community = c?.let { PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) },
                trackCount = (s?.get(1) as Number?)?.toLong() ?: 0,
                durationSec = (s?.get(2) as Number?)?.toLong() ?: 0,
                createdAt = p.createdAt,
                updatedAt = p.updatedAt,
                canEdit = canEdit(p, me),
                tracks = withTracks,
            )
        }
    }

    private fun nowPlayingMany(userIds: List<UUID>, me: UUID): Map<UUID, NowPlayingOut> {
        if (userIds.isEmpty()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            "select user_id, track_id, started_at, ends_at from now_playing where user_id in (?1) and ends_at > now()",
        ).setParameter(1, userIds.distinct()).resultList as List<Array<Any?>>
        val tracks = renderByIds(rows.map { it[1] as UUID }, me).associateBy { it.id }
        val now = Instant.now()
        val active = rows.mapNotNull { r ->
            val track = tracks[r[1] as UUID] ?: return@mapNotNull null
            val started = instant(r[2])
            (r[0] as UUID) to NowPlayingOut(
                r[0] as UUID, true, track, started, instant(r[3]),
                Duration.between(started, now).seconds.toInt().coerceIn(0, track.durationSec),
            )
        }.toMap()
        return userIds.associateWith { active[it] ?: NowPlayingOut(it, false, null, null, null, null) }
    }

    private fun pushToFriends(me: UUID, out: NowPlayingOut) {
        val to = friends.friendIdsOf(me)
        if (to.isEmpty()) return
        bus.publishToUsers(to, Envelope(t = FrameTypes.NOW_PLAYING, d = mapper.valueToTree(out)))
    }

    private fun canEdit(p: Playlist, me: UUID): Boolean =
        p.ownerId == me || (p.communityId != null && communities.roleOf(p.communityId!!, me) in setOf("admin", "owner"))

    private fun visiblePlaylist(id: UUID, me: UUID): Playlist {
        val p = Playlist.findById(id)
        if (p == null || p.deletedAt != null) throw ApiException.notFound("плейлист не найден")
        val visible = p.isPublic || p.ownerId == me || (p.communityId != null && communities.roleOf(p.communityId!!, me) != null)
        if (!visible) throw ApiException.notFound("плейлист не найден")
        return p
    }

    private fun editablePlaylist(me: UUID, id: UUID): Playlist {
        val p = visiblePlaylist(id, me)
        if (!canEdit(p, me)) throw ApiException.forbidden("править плейлист может владелец или admin сообщества")
        return p
    }

    private fun activeTrack(id: UUID): Track {
        val t = Track.findById(id)
        if (t == null || t.deletedAt != null) throw ApiException.notFound("трек не найден")
        return t
    }

    private fun ownTrack(me: UUID, id: UUID): Track {
        val t = activeTrack(id)
        if (t.uploaderId != me) throw ApiException.forbidden("править и удалять трек может только тот, кто его загрузил")
        return t
    }

    private fun requireImage(id: UUID, me: UUID) {
        val m = media.requireOwned(id, me)
        if (!m.contentType.startsWith("image/")) throw bad("invalid_cover", "обложка должна быть картинкой")
    }

    private fun text(v: String?, field: String): String? {
        val t = v?.trim() ?: return null
        if (t.length > MAX_TEXT) throw bad("invalid_$field", "$field длиннее $MAX_TEXT символов")
        return t.ifEmpty { null }
    }

    private fun longText(v: String?): String? {
        val t = v?.trim() ?: return null
        if (t.length > 2000) throw bad("invalid_description", "описание длиннее 2000 символов")
        return t.ifEmpty { null }
    }

    private fun bpm(v: Int?): Int? {
        if (v == null) return null
        if (v !in 20..400) throw bad("invalid_bpm", "bpm: 20–400")
        return v
    }

    private fun tags(v: List<String>?): List<String> {
        val t = v.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (t.size > MAX_TAGS) throw bad("invalid_tags", "не больше $MAX_TAGS тегов")
        if (t.any { it.length > MAX_TAG }) throw bad("invalid_tags", "тег длиннее $MAX_TAG символов")
        return t
    }

    private fun like(q: String) = "%" + q.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun instant(v: Any?): Instant = when (v) {
        is Instant -> v
        is OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }

    private fun exec(sql: String, vararg params: Any) {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        q.executeUpdate()
    }

    private fun count(sql: String, vararg params: Any): Long {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return (q.singleResult as Number).toLong()
    }

    @Suppress("UNCHECKED_CAST")
    private fun counts(sql: String, ids: List<UUID>): Map<UUID, Long> =
        (em.createNativeQuery(sql).setParameter(1, ids).resultList as List<Array<Any?>>)
            .associate { it[0] as UUID to (it[1] as Number).toLong() }

    private fun bad(code: String, msg: String) = ApiException.badRequest(code, msg)
}
