package dev.offreel.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.offreel.R
import dev.offreel.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit

/** Queues one download job per reel. Jobs survive the app being closed and retry on network loss. */
class Downloads(private val context: Context, private val settings: SettingsStore) {
    private val work get() = WorkManager.getInstance(context)

    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    /** Live download progress (0–100) per reel id, for the "Saving now" list. */
    val progress: StateFlow<Map<Long, Float>> = _progress

    fun setProgress(reelId: Long, percent: Float?) {
        _progress.update { if (percent == null) it - reelId else it + (reelId to percent.coerceIn(0f, 100f)) }
    }

    suspend fun enqueue(reelId: Long) {
        val wifiOnly = settings.current().wifiOnly
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_REEL to reelId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name(reelId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(reelId: Long) {
        work.cancelUniqueWork(name(reelId))
    }

    private fun name(reelId: Long) = "download-$reelId"

    companion object {
        const val TAG = "download"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 7001

        /** Only used on Android 10–11, where expedited work runs as a foreground service. */
        fun foregroundInfo(context: Context): ForegroundInfo {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Saving reels", NotificationManager.IMPORTANCE_LOW)
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Saving reel")
                .setOngoing(true)
                .setSilent(true)
                .build()
            return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }
}
