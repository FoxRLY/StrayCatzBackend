package org.example.presence.mapper

import org.example.presence.dto.Doing
import org.example.presence.dto.Listening
import org.example.presence.dto.Me
import org.example.presence.dto.Person
import org.example.presence.model.AppUser
import org.example.presence.model.Status

object PresenceMapper {

    fun toMe(u: AppUser): Me = Me(
        handle = u.handle,
        displayName = u.displayName,
        avatarUrl = u.avatarUrl,
        email = u.email,
        status = u.status,
        mood = u.mood,
        tagline = u.tagline,
        doing = doingOf(u),
        listening = listeningOf(u)
    )

    /** Невидимки возвращаются со status = invisible без doing и listening. */
    fun toPerson(u: AppUser): Person {
        val hidden = u.status == Status.invisible
        return Person(
            handle = u.handle,
            displayName = u.displayName,
            avatarUrl = u.avatarUrl,
            status = u.status,
            mood = if (hidden) null else u.mood,
            tagline = u.tagline,
            doing = if (hidden) null else doingOf(u),
            listening = if (hidden) null else listeningOf(u)
        )
    }

    private fun doingOf(u: AppUser): Doing? =
        if (u.doingApp == null && u.doingActivity == null) null
        else Doing(app = u.doingApp, activity = u.doingActivity)

    private fun listeningOf(u: AppUser): Listening? =
        if (u.listeningTrack == null && u.listeningArtist == null) null
        else Listening(track = u.listeningTrack, artist = u.listeningArtist)
}
