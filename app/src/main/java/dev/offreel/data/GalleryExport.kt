package dev.offreel.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies saved reels into the phone's shared Movies/offreel folder, where the gallery and file
 * managers see them and where they survive uninstalling offreel. No storage permission is needed:
 * since Android 10 an app may add its own files to MediaStore.
 */
object GalleryExport {
    private const val TAG = "offreel.gallery"
    val FOLDER = "${Environment.DIRECTORY_MOVIES}/offreel"

    enum class Result { SAVED, ALREADY_THERE, NOT_DOWNLOADED, FAILED }

    suspend fun save(context: Context, reel: ReelEntity): Result = withContext(Dispatchers.IO) {
        val src = reel.videoPath?.let(::File)?.takeIf { reel.status == ReelStatus.READY && it.exists() }
            ?: return@withContext Result.NOT_DOWNLOADED
        val author = reel.author?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.trim('_')?.takeIf { it.isNotEmpty() }
        val name = "offreel_${author ?: "reel"}_${reel.shortcode}.mp4"
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Already exported earlier? (We can always see files we added ourselves.)
        resolver.query(
            collection, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$FOLDER%"), null,
        )?.use { if (it.moveToFirst()) return@withContext Result.ALREADY_THERE }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@withContext Result.FAILED
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            Result.SAVED
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            resolver.delete(uri, null, null)
            Result.FAILED
        }
    }

    /** Saves several; returns a short message for a toast. */
    suspend fun saveAll(context: Context, reels: List<ReelEntity>): String {
        val results = reels.map { save(context, it) }
        val saved = results.count { it == Result.SAVED }
        val already = results.count { it == Result.ALREADY_THERE }
        val notReady = results.count { it == Result.NOT_DOWNLOADED }
        val failed = results.count { it == Result.FAILED }
        return buildList {
            if (saved > 0) add(if (saved == 1 && reels.size == 1) "Saved to gallery (Movies/offreel)" else "$saved saved to gallery")
            if (already > 0) add(if (reels.size == 1) "Already in your gallery" else "$already already there")
            if (notReady > 0) add(if (reels.size == 1) "Not downloaded yet" else "$notReady not downloaded yet")
            if (failed > 0) add("$failed failed")
        }.joinToString(" · ")
    }
}
