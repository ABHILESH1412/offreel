package dev.offreel.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface OffreelDao {
    // ---- groups ----

    @Query(
        """
        SELECT g.id, g.name, g.createdAt, g.isDefault,
          (SELECT COUNT(*) FROM collection_items i WHERE i.groupId = g.id) AS reelCount,
          (SELECT r.thumbPath FROM reels r JOIN collection_items i ON i.reelId = r.id
             WHERE i.groupId = g.id AND r.thumbPath IS NOT NULL
             ORDER BY i.addedAt DESC LIMIT 1) AS coverPath,
          (SELECT COUNT(*) FROM reels r JOIN collection_items i ON i.reelId = r.id
             WHERE i.groupId = g.id AND r.status IN ('pending', 'downloading')) AS pendingCount
        FROM collections g
        ORDER BY g.isDefault DESC, g.createdAt DESC
        """
    )
    fun observeGroupSummaries(): Flow<List<GroupSummary>>

    @Query("SELECT * FROM collections ORDER BY isDefault DESC, createdAt DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM collections WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultGroup(): GroupEntity?

    @Query("SELECT * FROM reels WHERE status IN ('pending', 'downloading') ORDER BY savedAt")
    fun observeActiveReels(): Flow<List<ReelEntity>>

    @Query("SELECT * FROM reels WHERE status = 'failed' ORDER BY savedAt DESC")
    fun observeFailedReels(): Flow<List<ReelEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeGroup(id: Long): Flow<GroupEntity?>

    @Query("SELECT name FROM collections WHERE id = :id")
    suspend fun groupName(id: Long): String?

    @Query("SELECT * FROM collections WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findGroupByName(name: String): GroupEntity?

    @Insert
    suspend fun insertGroup(group: GroupEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun renameGroup(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    // ---- reels ----

    @Query("SELECT * FROM reels WHERE shortcode = :shortcode")
    suspend fun findReel(shortcode: String): ReelEntity?

    @Query("SELECT * FROM reels WHERE id = :id")
    suspend fun getReel(id: Long): ReelEntity?

    @Insert
    suspend fun insertReel(reel: ReelEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: GroupItem): Long

    @Query(
        """
        SELECT r.* FROM reels r JOIN collection_items i ON i.reelId = r.id
        WHERE i.groupId = :groupId ORDER BY i.addedAt DESC
        """
    )
    fun observeReelsInGroup(groupId: Long): Flow<List<ReelEntity>>

    @Query("SELECT * FROM reels ORDER BY savedAt DESC")
    fun observeAllReels(): Flow<List<ReelEntity>>

    @Query(
        """
        SELECT * FROM reels WHERE id IN (SELECT reelId FROM collection_items WHERE groupId IN (:groupIds))
        ORDER BY savedAt DESC
        """
    )
    fun observeReelsInGroups(groupIds: List<Long>): Flow<List<ReelEntity>>

    @Query("SELECT groupId FROM collection_items WHERE reelId = :reelId")
    suspend fun groupIdsOf(reelId: Long): List<Long>

    @Query("SELECT g.name FROM collections g JOIN collection_items i ON i.groupId = g.id WHERE i.reelId = :reelId")
    suspend fun groupNamesOf(reelId: Long): List<String>

    @Query("SELECT reelId FROM collection_items WHERE groupId = :groupId")
    suspend fun reelIdsIn(groupId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM collection_items WHERE reelId = :reelId")
    suspend fun groupCountOf(reelId: Long): Int

    @Query("DELETE FROM collection_items WHERE reelId = :reelId AND groupId = :groupId")
    suspend fun removeItem(reelId: Long, groupId: Long)

    @Query("DELETE FROM reels WHERE id = :id")
    suspend fun deleteReel(id: Long)

    @Query("UPDATE reels SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query(
        """
        UPDATE reels SET status = 'ready', error = NULL, videoPath = :videoPath, thumbPath = :thumbPath,
          author = COALESCE(author, :author), caption = COALESCE(:caption, caption),
          durationSec = :durationSec, width = :width, height = :height, sizeBytes = :sizeBytes
        WHERE id = :id
        """
    )
    suspend fun markReady(
        id: Long, videoPath: String, thumbPath: String?, author: String?, caption: String?,
        durationSec: Double?, width: Int?, height: Int?, sizeBytes: Long?,
    )

    @Query("SELECT id FROM reels WHERE status = 'failed'")
    suspend fun failedIds(): List<Long>

    @Query("SELECT id FROM reels WHERE status IN ('pending', 'downloading')")
    suspend fun unfinishedIds(): List<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM reels")
    fun observeStorageBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM reels WHERE status = 'failed'")
    fun observeFailedCount(): Flow<Int>
}

@Database(
    entities = [GroupEntity::class, ReelEntity::class, GroupItem::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class OffreelDb : RoomDatabase() {
    abstract fun dao(): OffreelDao

    companion object {
        fun create(context: Context): OffreelDb =
            Room.databaseBuilder(context, OffreelDb::class.java, "offreel.db").build()
    }
}
