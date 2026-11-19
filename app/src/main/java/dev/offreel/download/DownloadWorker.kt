package dev.offreel.download

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.graphics.scale
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.offreel.data.GalleryExport
import dev.offreel.data.ReelStatus
import dev.offreel.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Downloads one reel with yt-dlp into app-private storage and makes a thumbnail for it. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_REEL = "reelId"
        private const val TAG = "offreel.download"

        /** Each yt-dlp run is a Python process; two at a time is plenty on a phone. */
        private val gate = Semaphore(2)

        private val STALE_YTDLP = TimeUnit.DAYS.toMillis(3)
        private val AFTER_FAILURE_YTDLP = TimeUnit.HOURS.toMillis(6)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = Downloads.foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val dao = graph.db.dao()
        val id = inputData.getLong(KEY_REEL, -1)
        val reel = dao.getReel(id) ?: return Result.success()   // deleted meanwhile
        if (reel.status == ReelStatus.READY && reel.videoPath?.let { File(it).exists() } == true) return Result.success()

        return gate.withPermit {
            dao.setStatus(id, ReelStatus.DOWNLOADING, null)
            try {
                val done = withContext(Dispatchers.IO) { download(id, reel.url) }
                dao.markReady(
                    id = id, videoPath = done.file.absolutePath, thumbPath = done.thumb?.absolutePath,
                    author = done.author, caption = done.caption, durationSec = done.duration,
                    width = done.width, height = done.height, sizeBytes = done.file.length(),
                )
                if (graph.settings.current().autoGallery) {
                    dao.getReel(id)?.let { GalleryExport.save(applicationContext, it) }
                }
                Result.success()
            } catch (e: CancellationException) {
                YoutubeDL.getInstance().destroyProcessById(processId(id))
                dao.setStatus(id, ReelStatus.PENDING, null)
                throw e
            } catch (e: Exception) {
                val raw = e.message.orEmpty()
                Log.w(TAG, "download $id failed (attempt $runAttemptCount): $raw")
                val kind = classify(raw)
                when {
                    kind == Failure.NETWORK && runAttemptCount < 4 -> {
                        dao.setStatus(id, ReelStatus.PENDING, null)
                        Result.retry()
                    }
                    // An extractor error is often fixed by a newer yt-dlp: update once, then try again.
                    kind == Failure.EXTRACTOR && runAttemptCount == 0 &&
                        Ytdl.updateIfStale(applicationContext, graph.settings, AFTER_FAILURE_YTDLP) -> {
                        dao.setStatus(id, ReelStatus.PENDING, null)
                        Result.retry()
                    }
                    else -> {
                        dao.setStatus(id, ReelStatus.FAILED, kind.message)
                        Result.success()
                    }
                }
            }
        }
    }

    private class Downloaded(
        val file: File, val thumb: File?, val author: String?, val caption: String?,
        duration: Double?, val width: Int?, val height: Int?, durationFromFile: Double? = null,
    ) {
        val duration: Double? = duration ?: durationFromFile
    }

    private fun videoDuration(video: File): Double? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(video.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
        }
    }.getOrNull()

    private fun processId(id: Long) = "download-$id"

    private suspend fun download(id: Long, url: String): Downloaded {
        val ctx = applicationContext
        val settings = ctx.graph.settings.current()
        Ytdl.init(ctx)
        Ytdl.updateIfStale(ctx, ctx.graph.settings, STALE_YTDLP)

        val dir = File(ctx.filesDir, "media").apply { mkdirs() }
        val base = "reel_$id"
        dir.listFiles { f -> f.name.startsWith("$base.") }?.forEach { it.delete() }   // leftovers from a failed try

        val request = YoutubeDLRequest(url).apply {
            addOption("--playlist-items", "1")          // carousel posts: first video only
            addOption("-f", "bv*+ba/b")
            // "res" is the shorter side, so 1080 means 1080x1920 for a vertical reel.
            addOption("-S", settings.quality.shortSide?.let { "res:$it,ext:mp4:m4a" } ?: "ext:mp4:m4a")
            addOption("--merge-output-format", "mp4")
            addOption("--no-mtime")
            addOption("--no-cache-dir")
            addOption("-o", File(dir, "$base.%(ext)s").absolutePath)
            addOption("--print", "after_move:%(.{filepath,uploader,channel,description,duration,width,height})j")
        }
        val downloads = ctx.graph.downloads
        downloads.setProgress(id, 0f)
        val response = try {
            YoutubeDL.getInstance().execute(request, processId(id)) { percent, _, _ ->
                // yt-dlp reports the video file, then the audio file, each from 0 to 100.
                if (percent >= 0) downloads.setProgress(id, percent)
            }
        } finally {
            downloads.setProgress(id, null)
        }
        val json = response.out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?.let { JSONObject(it) }
            ?: throw IllegalStateException("yt-dlp printed no result: ${response.err.takeLast(300)}")

        val file = json.optString("filepath").takeIf { it.isNotEmpty() }?.let(::File)
            ?.takeIf { it.exists() }
            ?: dir.listFiles { f -> f.name.startsWith("$base.") }?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp finished but no file was written")

        return Downloaded(
            file = file,
            thumb = makeThumbnail(file, File(File(ctx.filesDir, "thumbs").apply { mkdirs() }, "$base.jpg")),
            // yt-dlp often has no duration for Instagram; read it from the file instead.
            durationFromFile = videoDuration(file),
            // "channel" is the @handle on Instagram; "uploader" is the display name.
            author = json.optStringOrNull("channel") ?: json.optStringOrNull("uploader"),
            caption = json.optStringOrNull("description"),
            duration = json.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
            width = json.optInt("width").takeIf { it > 0 },
            height = json.optInt("height").takeIf { it > 0 },
        )
    }

    private fun makeThumbnail(video: File, out: File): File? = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(video.absolutePath)
            val frame = r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val w = 360
            val scaled = frame.scale(w, (frame.height * w.toFloat() / frame.width).toInt())
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "thumbnail failed", e)
        null
    }

    private enum class Failure(val message: String) {
        PHOTO("Photo post: only videos can be saved for now"),
        BLOCKED("Instagram didn't allow downloading this one"),
        NETWORK("No connection"),
        EXTRACTOR("Download failed"),
    }

    private fun classify(raw: String): Failure {
        val m = raw.lowercase()
        return when {
            "no video in this post" in m -> Failure.PHOTO
            "empty media response" in m || "login" in m || "rate-limit" in m || "not available" in m ||
                "private" in m -> Failure.BLOCKED
            "unable to download" in m || "timed out" in m || "network" in m || "connection" in m ||
                "resolve" in m || "errno" in m -> Failure.NETWORK
            else -> Failure.EXTRACTOR
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "NA" }
