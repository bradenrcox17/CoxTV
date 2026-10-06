package com.coxtv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val CHANNEL_SELECT =
    "SELECT c.*, (f.channelId IS NOT NULL) AS favorite, f.position AS favoritePosition FROM channels c " +
        "LEFT JOIN favorites f ON f.channelId = c.id"

@Dao
abstract class ChannelDao {
    @Query("$CHANNEL_SELECT ORDER BY c.sortOrder")
    abstract fun observeAll(): Flow<List<Channel>>

    @Query("SELECT groupName FROM channels GROUP BY groupName ORDER BY MIN(sortOrder)")
    abstract fun observeGroups(): Flow<List<String>>

    @Query("$CHANNEL_SELECT WHERE c.id = :id")
    abstract suspend fun get(id: String): Channel?

    /** Lightweight rows for the in-memory search index. */
    @Query("SELECT id AS channelId, name AS channelName, groupName, epgId FROM channels ORDER BY sortOrder")
    abstract suspend fun searchRows(): List<ChannelHit>

    @Query("SELECT * FROM channels")
    abstract suspend fun allEntities(): List<ChannelEntity>

    @Query("SELECT * FROM channels")
    abstract fun allEntitiesBlocking(): List<ChannelEntity>

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    abstract fun setEpgIdBlocking(id: String, epgId: String)

    @Query("DELETE FROM channels")
    abstract suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(channels: List<ChannelEntity>)

    @Transaction
    open suspend fun replaceAll(channels: List<ChannelEntity>) {
        clear()
        channels.chunked(500).forEach { insertAll(it) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    abstract suspend fun removeFavorite(channelId: String)

    /** Every favorite id (including ones the current playlist no longer has), in list order. */
    @Query("SELECT channelId FROM favorites ORDER BY position, addedAt")
    abstract suspend fun favoriteIds(): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM favorites")
    abstract suspend fun nextFavoritePosition(): Int

    @Query("UPDATE favorites SET position = :position WHERE channelId = :channelId")
    abstract suspend fun setFavoritePosition(channelId: String, position: Int)

    @Transaction
    open suspend fun reorderFavorites(ids: List<String>) {
        ids.forEachIndexed { i, id -> setFavoritePosition(id, i) }
    }
}

@Dao
interface ProgramDao {
    @Query("SELECT * FROM programs WHERE +endMs > :from AND startMs < :to ORDER BY startMs")
    fun observeInRange(from: Long, to: Long): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM programs WHERE epgId = :epgId AND endMs > :from ORDER BY startMs LIMIT :limit")
    suspend fun upcoming(epgId: String, from: Long, limit: Int): List<ProgramEntity>

    /** Programs for just these channels (the guide asks only for rows near the screen). */
    @Query("SELECT * FROM programs WHERE epgId IN (:ids) AND endMs > :from AND startMs < :to ORDER BY startMs")
    suspend fun inRangeFor(ids: List<String>, from: Long, to: Long): List<ProgramEntity>

    /**
     * Everything airing at [now], with its channel. "+endMs" steers SQLite to the startMs
     * index: few programmes have started yet still run, while most of the window ends later.
     */
    @Query(
        """SELECT c.id AS channelId, c.name AS channelName, c.groupName AS groupName, p.title AS title, p.endMs AS endMs
        FROM programs p JOIN channels c ON c.epgId = p.epgId
        WHERE p.startMs <= :now AND +p.endMs > :now""",
    )
    suspend fun airingNow(now: Long): List<NowHit>

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun count(): Int

    @Query("DELETE FROM programs")
    fun clearBlocking()

    @Insert
    fun insertBlocking(programs: List<ProgramEntity>)
}
