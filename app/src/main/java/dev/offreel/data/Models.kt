package dev.offreel.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-made group (collection) of reels and posts. */
@Entity(tableName = "collections")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** The Default group: every save lands here unless the user picks another group. */
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
)

object ReelStatus {
    const val PENDING = "pending"
    const val DOWNLOADING = "downloading"
    const val READY = "ready"
    const val FAILED = "failed"
}

/** One saved Instagram reel or post. The link is kept even if the download fails. */
@Entity(tableName = "reels", indices = [Index(value = ["shortcode"], unique = true)])
data class ReelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shortcode: String,
    /** Canonical link without tracking parameters, e.g. https://www.instagram.com/reel/ABC/ */
    val url: String,
    /** "reel" or "p" (post). */
    val kind: String,
    val author: String? = null,
    val caption: String? = null,
    val durationSec: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoPath: String? = null,
    val thumbPath: String? = null,
    val sizeBytes: Long? = null,
    val status: String = ReelStatus.PENDING,
    val error: String? = null,
    val savedAt: Long,
)

@Entity(
    tableName = "collection_items",
    primaryKeys = ["reelId", "groupId"],
    foreignKeys = [
        ForeignKey(entity = ReelEntity::class, parentColumns = ["id"], childColumns = ["reelId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("groupId")],
)
data class GroupItem(
    val reelId: Long,
    val groupId: Long,
    val addedAt: Long,
)

/** A group with what the Home grid shows on its card. */
data class GroupSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val reelCount: Int,
    val coverPath: String?,
    val pendingCount: Int,
    val isDefault: Boolean,
)
