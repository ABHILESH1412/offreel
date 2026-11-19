package dev.offreel.data

import androidx.room.withTransaction
import dev.offreel.download.Downloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What happened when the user saved a reel. */
data class SaveResult(
    val reelId: Long,
    /** The group the reel is in now (the Default group, or the one picked before the link arrived). */
    val groupId: Long,
    val groupName: String,
    /** The reel was saved before; nothing new was downloaded. */
    val existed: Boolean,
) {
    fun message(): String = if (existed) "Already saved in $groupName" else "Saved to $groupName"
}

class Repository(
    private val db: OffreelDb,
    private val downloads: Downloads,
) {
    companion object {
        const val DEFAULT_GROUP_NAME = "Default"
    }

    private val dao = db.dao()
    private val defaultLock = Mutex()

    val groupSummaries: Flow<List<GroupSummary>> = dao.observeGroupSummaries()
    val groups: Flow<List<GroupEntity>> = dao.observeGroups()
    val allReels: Flow<List<ReelEntity>> = dao.observeAllReels()
    val activeReels: Flow<List<ReelEntity>> = dao.observeActiveReels()
    val failedReels: Flow<List<ReelEntity>> = dao.observeFailedReels()
    val storageBytes: Flow<Long> = dao.observeStorageBytes()
    val failedCount: Flow<Int> = dao.observeFailedCount()

    fun group(id: Long): Flow<GroupEntity?> = dao.observeGroup(id)
    fun reelsIn(groupId: Long): Flow<List<ReelEntity>> = dao.observeReelsInGroup(groupId)

    /** Reels in any of [groupIds]; every reel when the set is empty. */
    fun reelsInGroups(groupIds: Set<Long>): Flow<List<ReelEntity>> =
        if (groupIds.isEmpty()) dao.observeAllReels() else dao.observeReelsInGroups(groupIds.toList())

    /** The Default group's id, creating the group the first time. */
    suspend fun defaultGroupId(): Long = defaultLock.withLock {
        dao.defaultGroup()?.id
            ?: dao.insertGroup(GroupEntity(name = DEFAULT_GROUP_NAME, createdAt = System.currentTimeMillis(), isDefault = true))
    }

    /** Creates a group, or returns the existing one with the same name (case-insensitive). */
    suspend fun createGroup(name: String): Long {
        val clean = name.trim().take(40)
        require(clean.isNotEmpty())
        dao.findGroupByName(clean)?.let { return it.id }
        return dao.insertGroup(GroupEntity(name = clean, createdAt = System.currentTimeMillis()))
    }

    suspend fun renameGroup(id: Long, name: String) {
        val clean = name.trim().take(40)
        if (clean.isNotEmpty()) dao.renameGroup(id, clean)
    }

    /**
     * Deletes groups (never Default). Reels that would be left in no group are either deleted with
     * their files, or, with [keepReels], moved to Default.
     */
    suspend fun deleteGroups(ids: Collection<Long>, keepReels: Boolean) {
        val defaultId = defaultGroupId()
        val targets = ids.filter { it != defaultId }
        val orphans = db.withTransaction {
            val now = System.currentTimeMillis()
            val reelIds = targets.flatMap { dao.reelIdsIn(it) }.toSet()
            targets.forEach { dao.deleteGroup(it) }
            reelIds.mapNotNull { rid ->
                when {
                    dao.groupCountOf(rid) > 0 -> null
                    keepReels -> { dao.insertItem(GroupItem(rid, defaultId, now)); null }
                    else -> dao.getReel(rid)?.also { dao.deleteReel(rid) }
                }
            }
        }
        orphans.forEach { downloads.cancel(it.id); deleteFiles(it) }
    }

    suspend fun deleteGroup(id: Long, keepReels: Boolean = false) = deleteGroups(listOf(id), keepReels)

    /**
     * Saves [link] and queues its download. It goes into [groupId], or the Default group when null.
     * A reel saved before is left where it is (and retried if its download had failed).
     */
    suspend fun save(link: InstagramLink, groupId: Long? = null, authorHint: String? = null): SaveResult {
        val target = groupId ?: defaultGroupId()
        val now = System.currentTimeMillis()
        val existing = dao.findReel(link.shortcode)
        if (existing != null) {
            if (groupId != null) dao.insertItem(GroupItem(existing.id, groupId, now))
            if (existing.status == ReelStatus.FAILED) retry(existing.id)
            val inGroup = dao.groupIdsOf(existing.id).firstOrNull() ?: target
            return SaveResult(existing.id, inGroup, dao.groupName(inGroup) ?: "a group", existed = true)
        }
        val reelId = db.withTransaction {
            val id = dao.insertReel(
                ReelEntity(shortcode = link.shortcode, url = link.url, kind = link.kind, author = authorHint, savedAt = now)
            )
            dao.insertItem(GroupItem(id, target, now))
            id
        }
        downloads.enqueue(reelId)
        return SaveResult(reelId, target, dao.groupName(target) ?: DEFAULT_GROUP_NAME, existed = false)
    }

    /** Moves a reel from one group to another (e.g. out of Default into Gym). Returns the new group's name. */
    suspend fun moveToGroup(reelId: Long, fromGroupId: Long?, toGroupId: Long): String {
        db.withTransaction {
            dao.insertItem(GroupItem(reelId, toGroupId, System.currentTimeMillis()))
            if (fromGroupId != null && fromGroupId != toGroupId) dao.removeItem(reelId, fromGroupId)
        }
        return dao.groupName(toGroupId) ?: "group"
    }

    /** Moves several reels from [fromGroupId] to [toGroupId] in one go. Returns the target group's name. */
    suspend fun moveManyToGroup(reelIds: Collection<Long>, fromGroupId: Long, toGroupId: Long): String {
        db.withTransaction {
            val now = System.currentTimeMillis()
            reelIds.forEach { id ->
                dao.insertItem(GroupItem(id, toGroupId, now))
                if (fromGroupId != toGroupId) dao.removeItem(id, fromGroupId)
            }
        }
        return dao.groupName(toGroupId) ?: "group"
    }

    /** Deletes several reels from every group, with their files. */
    suspend fun deleteReels(reelIds: Collection<Long>) {
        reelIds.forEach { deleteReel(it) }
    }

    suspend fun addToGroup(reelId: Long, groupId: Long) {
        dao.insertItem(GroupItem(reelId, groupId, System.currentTimeMillis()))
    }

    /** Removes the reel from one group; if it is in no group any more, deletes it and its files. */
    suspend fun removeFromGroup(reelId: Long, groupId: Long) {
        val orphan = db.withTransaction {
            dao.removeItem(reelId, groupId)
            if (dao.groupCountOf(reelId) == 0) dao.getReel(reelId)?.also { dao.deleteReel(reelId) } else null
        }
        orphan?.let { deleteFiles(it) }
    }

    /** Deletes the reel from every group, with its files. */
    suspend fun deleteReel(reelId: Long) {
        val reel = dao.getReel(reelId) ?: return
        downloads.cancel(reelId)
        dao.deleteReel(reelId)
        deleteFiles(reel)
    }

    suspend fun retry(reelId: Long) {
        dao.setStatus(reelId, ReelStatus.PENDING, null)
        downloads.enqueue(reelId)
    }

    suspend fun retryAllFailed() {
        dao.failedIds().forEach { retry(it) }
    }

    suspend fun groupIdsOf(reelId: Long) = dao.groupIdsOf(reelId)

    private fun deleteFiles(reel: ReelEntity) {
        reel.videoPath?.let { File(it).delete() }
        reel.thumbPath?.let { File(it).delete() }
    }
}
