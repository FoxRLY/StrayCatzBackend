package org.example.mapper

import org.example.dto.Room
import org.example.dto.RoomCard
import org.example.dto.GuestbookEntry as GuestbookEntryDto
import org.example.model.GuestbookEntry
import org.example.model.Room as RoomEntity

object RoomMapper {

    fun toCard(r: RoomEntity): RoomCard = RoomCard(
        handle = r.handle,
        title = r.title,
        wallpaperThumbUrl = r.wallpaperUrl,
        open = r.open
    )

    fun toRoom(r: RoomEntity, ownerHandle: String): Room = Room(
        handle = r.handle,
        ownerHandle = ownerHandle,
        title = r.title,
        wallpaperUrl = r.wallpaperUrl,
        layout = r.layout,
        dictionary = r.dictionary,
        backdropDim = r.backdropDim,
        open = r.open
    )

    fun toGuestbookEntry(e: GuestbookEntry): GuestbookEntryDto = GuestbookEntryDto(
        id = e.id.toString(),
        authorHandle = e.authorHandle,
        body = e.body,
        createdAt = e.createdAt
    )
}
