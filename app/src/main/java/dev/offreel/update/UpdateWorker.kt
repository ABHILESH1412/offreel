package dev.offreel.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.offreel.data.Settings
import dev.offreel.graph
import java.util.concurrent.TimeUnit

/** The automatic update check: about every 12 hours, and when the app is opened (at most every 6 hours). */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = applicationContext.graph.settings
        if (!Updates.canInstall || !store.current().autoUpdate) return Result.success()
        store.setLastUpdateCheck(System.currentTimeMillis())
        Updates.checkAndInstall(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "update-check"
        private const val NOW = "update-check-now"
        private val OPEN_INTERVAL = TimeUnit.HOURS.toMillis(6)

        private fun constraints(s: Settings) = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        /** Turns the periodic check on or off to match the settings. Nothing runs while it's off. */
        fun schedule(context: Context, s: Settings) {
            val work = WorkManager.getInstance(context)
            if (!Updates.canInstall || !s.autoUpdate) {
                work.cancelUniqueWork(PERIODIC)
                return
            }
            work.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS).setConstraints(constraints(s)).build(),
            )
        }

        /** On app open: check if the last check is older than 6 hours. */
        fun checkSoon(context: Context, s: Settings) {
            if (!Updates.canInstall || !s.autoUpdate) return
            if (System.currentTimeMillis() - s.lastUpdateCheck < OPEN_INTERVAL) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints(s)).build(),
            )
        }
    }
}
