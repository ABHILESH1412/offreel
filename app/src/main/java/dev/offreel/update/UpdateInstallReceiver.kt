package dev.offreel.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Receives the result of an update install from Android's package installer. */
class UpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_VERSION = "dev.offreel.update.VERSION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getStringExtra(EXTRA_VERSION) ?: "the update"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i("offreel.update", "install status $status for $version: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) Updates.askUser(context, confirm, version)
            }
            PackageInstaller.STATUS_SUCCESS -> Updates.setStatus(Updates.Status.UpToDate(version))
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updates.setStatus(Updates.Status.Available(version))
            else -> Updates.setStatus(
                Updates.Status.Failed(
                    "Update to $version failed" +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: "")
                )
            )
        }
    }
}
