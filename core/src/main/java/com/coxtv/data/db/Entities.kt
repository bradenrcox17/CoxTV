package com.coxtv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "channels", indices = [Index("groupName"), Index("epgId")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val number: Int,
    val name: String,
    val logo: String?,
    val groupName: String,
    val streamUrl: String,
    /** EPG id exactly as the source provided it (tvg-id / epg_channel_id). */
    val epgIdRaw: String?,
    /** Effective EPG id. May be remapped to an XMLTV channel id by display-name matching. */
    val epgId: String?,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelId: String,
    val addedAt: Long,
)

@Entity(
    tableName = "programs",
    indices = [Index(value = ["epgId", "startMs"]), Index("endMs"), Index("startMs")],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val epgId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
)

/** A show airing now whose title matched a search, with its channel. */
data class NowHit(
    val channelId: String,
    val channelName: String,
    val groupName: String,
    val title: String,
    val endMs: Long,
)

/** A channel whose name matched a search. */
data class ChannelHit(
    val channelId: String,
    val channelName: String,
    val groupName: String,
    val epgId: String?,
)

/** Channel joined with its favorite flag. */
data class Channel(
    val id: String,
    val sortOrder: Int,
    val number: Int,
    val name: String,
    val logo: String?,
    val groupName: String,
    val streamUrl: String,
    val epgIdRaw: String?,
    val epgId: String?,
    val favorite: Boolean,
)
