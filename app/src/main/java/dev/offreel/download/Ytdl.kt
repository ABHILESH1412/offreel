package dev.offreel.download

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dev.offreel.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Wraps the bundled yt-dlp (Python) and ffmpeg. Everything here blocks, so call it off the main thread. */
object Ytdl {
    private const val TAG = "offreel.ytdl"
    private var initialized = false
    private val updateLock = Mutex()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        initialized = true
    }

    fun version(context: Context): String? =
        runCatching { init(context); YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()

    /**
     * Updates yt-dlp to the latest stable release. Instagram changes often break older versions,
     * so this runs before a download when the last update is older than [maxAgeMs].
     * Returns true when a newer version was installed.
     */
    suspend fun updateIfStale(context: Context, store: SettingsStore, maxAgeMs: Long): Boolean {
        if (System.currentTimeMillis() - store.current().ytdlpUpdatedAt < maxAgeMs) return false
        return update(context, store) == YoutubeDL.UpdateStatus.DONE
    }

    suspend fun update(context: Context, store: SettingsStore): YoutubeDL.UpdateStatus? = updateLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                init(context)
                val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                store.setYtdlpUpdatedAt(System.currentTimeMillis())
                Log.i(TAG, "yt-dlp update: $status, now ${version(context)}")
                status
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed", e)
                null
            }
        }
    }
}
