package org.example.rest

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.example.service.MusicService
import java.util.UUID

/** Музыка: треки, «моя музыка», друзья, сообщества, плейлисты, «сейчас слушает». */
@Path("/api/music")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MusicResource(
    private val currentUser: CurrentUser,
    private val music: MusicService,
) {
    // ================================================================ треки

    /** {audioMediaId, title, artist, durationSec, album?, coverMediaId?, bpm?, tags?, communitySlug?} -> 201 */
    @POST
    @Path("/tracks")
    fun upload(@HeaderParam("Authorization") auth: String?, req: TrackIn?): Response =
        Response.status(201).entity(music.upload(me(auth), req ?: TrackIn())).build()

    @GET
    @Path("/tracks/{id}")
    fun track(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): TrackOut = music.get(id, me(auth))

    /** Загрузивший: {title?, artist?, album?, bpm?, tags?, coverMediaId?, clearCover?} */
    @PATCH
    @Path("/tracks/{id}")
    fun updateTrack(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: TrackPatchIn?): TrackOut =
        music.update(me(auth), id, req ?: TrackPatchIn())

    @DELETE
    @Path("/tracks/{id}")
    fun deleteTrack(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        music.delete(me(auth), id)
        return Response.noContent().build()
    }

    /** Поиск по названию, исполнителю, альбому: ?q=pegboard&limit=30&offset=0 */
    @GET
    @Path("/search")
    fun search(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("30") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): TrackPageOut = music.search(me(auth), q, limit, offset)

    // ================================================================ моя музыка / друзья

    /** «Моя музыка». ?q — фильтр внутри. */
    @GET
    @Path("/library")
    fun library(
        @HeaderParam("Authorization") auth: String?,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): TrackPageOut {
        val me = me(auth)
        return music.library(me, me, q, limit, offset)
    }

    /** «Добавить себе». */
    @PUT
    @Path("/library/{trackId}")
    fun addToLibrary(@HeaderParam("Authorization") auth: String?, @PathParam("trackId") trackId: UUID): TrackOut =
        music.addToLibrary(me(auth), trackId)

    @DELETE
    @Path("/library/{trackId}")
    fun removeFromLibrary(@HeaderParam("Authorization") auth: String?, @PathParam("trackId") trackId: UUID): Response {
        music.removeFromLibrary(me(auth), trackId)
        return Response.noContent().build()
    }

    /** Музыка другого человека (друга). */
    @GET
    @Path("/users/{userId}/library")
    fun userLibrary(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("userId") userId: UUID,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): TrackPageOut = music.library(me(auth), userId, q, limit, offset)

    @GET
    @Path("/users/{userId}/playlists")
    fun userPlaylists(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID): List<PlaylistOut> =
        music.userPlaylists(userId, me(auth))

    /** Что слушает человек (себе и друзьям видно, остальным playing=false). */
    @GET
    @Path("/users/{userId}/now-playing")
    fun userNowPlaying(@HeaderParam("Authorization") auth: String?, @PathParam("userId") userId: UUID): NowPlayingOut =
        music.nowPlaying(userId, me(auth))

    /** Друзья: сколько треков у каждого и что играет. Играющие сверху. */
    @GET
    @Path("/friends")
    fun friends(@HeaderParam("Authorization") auth: String?): List<FriendMusicOut> = music.friendsMusic(me(auth))

    /** Только те друзья, у кого сейчас что-то играет («слушают то же самое» и т.п.). */
    @GET
    @Path("/listening")
    fun listening(@HeaderParam("Authorization") auth: String?): List<NowPlayingOut> = music.friendsListening(me(auth))

    // ================================================================ сообщества

    @GET
    @Path("/communities/{slug}/tracks")
    fun communityTracks(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @QueryParam("q") q: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): TrackPageOut = music.communityTracks(slug, me(auth), q, limit, offset)

    /** Добавить трек в музыку сообщества (участник). */
    @PUT
    @Path("/communities/{slug}/tracks/{trackId}")
    fun addToCommunity(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @PathParam("trackId") trackId: UUID,
    ): TrackOut = music.addToCommunity(me(auth), slug, trackId)

    /** Убрать (кто добавил или admin). */
    @DELETE
    @Path("/communities/{slug}/tracks/{trackId}")
    fun removeFromCommunity(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("slug") slug: String,
        @PathParam("trackId") trackId: UUID,
    ): Response {
        music.removeFromCommunity(me(auth), slug, trackId)
        return Response.noContent().build()
    }

    @GET
    @Path("/communities/{slug}/playlists")
    fun communityPlaylists(@HeaderParam("Authorization") auth: String?, @PathParam("slug") slug: String): List<PlaylistOut> =
        music.communityPlaylists(slug, me(auth))

    // ================================================================ плейлисты

    @GET
    @Path("/playlists")
    fun myPlaylists(@HeaderParam("Authorization") auth: String?): List<PlaylistOut> = music.myPlaylists(me(auth))

    /** {title, description?, coverMediaId?, isPublic?, communitySlug?, trackIds?} -> 201 */
    @POST
    @Path("/playlists")
    fun createPlaylist(@HeaderParam("Authorization") auth: String?, req: PlaylistIn?): Response =
        Response.status(201).entity(music.createPlaylist(me(auth), req ?: PlaylistIn())).build()

    /** С треками по порядку. */
    @GET
    @Path("/playlists/{id}")
    fun playlist(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): PlaylistOut =
        music.playlist(id, me(auth))

    @PATCH
    @Path("/playlists/{id}")
    fun updatePlaylist(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: PlaylistPatchIn?): PlaylistOut =
        music.updatePlaylist(me(auth), id, req ?: PlaylistPatchIn())

    @DELETE
    @Path("/playlists/{id}")
    fun deletePlaylist(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID): Response {
        music.deletePlaylist(me(auth), id)
        return Response.noContent().build()
    }

    @PUT
    @Path("/playlists/{id}/tracks/{trackId}")
    fun addToPlaylist(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("id") id: UUID,
        @PathParam("trackId") trackId: UUID,
    ): PlaylistOut = music.addToPlaylist(me(auth), id, trackId)

    @DELETE
    @Path("/playlists/{id}/tracks/{trackId}")
    fun removeFromPlaylist(
        @HeaderParam("Authorization") auth: String?,
        @PathParam("id") id: UUID,
        @PathParam("trackId") trackId: UUID,
    ): PlaylistOut = music.removeFromPlaylist(me(auth), id, trackId)

    /** {trackIds: [...все треки в новом порядке]} */
    @PUT
    @Path("/playlists/{id}/order")
    fun reorder(@HeaderParam("Authorization") auth: String?, @PathParam("id") id: UUID, req: TrackOrderIn?): PlaylistOut =
        music.reorderPlaylist(me(auth), id, req?.trackIds ?: emptyList())

    // ================================================================ сейчас слушает

    @GET
    @Path("/now-playing")
    fun myNowPlaying(@HeaderParam("Authorization") auth: String?): NowPlayingOut {
        val me = me(auth)
        return music.nowPlaying(me, me)
    }

    /** Включил трек (и при перемотке/возобновлении): {trackId, positionSec?}. */
    @PUT
    @Path("/now-playing")
    fun startPlaying(@HeaderParam("Authorization") auth: String?, req: NowPlayingIn?): NowPlayingOut {
        val trackId = req?.trackId ?: throw ApiException.badRequest("invalid_track", "нужен trackId")
        return music.startPlaying(me(auth), trackId, req.positionSec)
    }

    /** Пауза/стоп. */
    @DELETE
    @Path("/now-playing")
    fun stopPlaying(@HeaderParam("Authorization") auth: String?): NowPlayingOut = music.stopPlaying(me(auth))

    private fun me(auth: String?): UUID = currentUser.require(auth).userId
}
