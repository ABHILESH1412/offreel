package dev.offreel.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.offreel.BuildConfig
import dev.offreel.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from this project's GitHub releases: find the latest release, download its
 * offreel-<version>.apk and install it over the running app. Android only accepts it if it is
 * signed with the same key, so a tampered download can't replace offreel.
 */
object Updates {
    private const val TAG = "offreel.update"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7002
    val REPO: String = BuildConfig.UPDATE_REPO

    /** Development builds are a different app ("…offreel.dev", debug key): they only report what's available. */
    val canInstall: Boolean get() = !BuildConfig.DEBUG

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data class UpToDate(val version: String) : Status
        data class Available(val version: String) : Status
        data class Downloading(val version: String, val percent: Int) : Status
        data class Installing(val version: String) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status
    fun setStatus(s: Status) { _status.value = s }

    /** Set by MainActivity: if an install needs the user's OK, show it now rather than as a notification. */
    @Volatile var appVisible = false

    private val runLock = Mutex()

    data class Release(val version: String, val apkUrl: String, val apkSize: Long)

    /** Checks GitHub and, if a newer release exists (and this build can install it), downloads and installs it. */
    suspend fun checkAndInstall(context: Context) = runLock.withLock {
        _status.value = Status.Checking
        val release = try {
            latestRelease()
        } catch (e: Exception) {
            Log.w(TAG, "check failed", e)
            _status.value = Status.Failed("Couldn't reach GitHub. Check your connection.")
            return@withLock
        }
        if (release == null || !isNewer(release.version, BuildConfig.VERSION_NAME)) {
            _status.value = Status.UpToDate(BuildConfig.VERSION_NAME)
            return@withLock
        }
        if (!canInstall) {
            _status.value = Status.Available(release.version)
            return@withLock
        }
        val apk = try {
            download(context, release)
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            _status.value = Status.Failed("Download of ${release.version} failed. It will try again later.")
            return@withLock
        } ?: return@withLock
        install(context, apk, release.version)
    }

    private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "offreel-updater")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            when (conn.responseCode) {
                404 -> return@withContext null   // no releases yet
                200 -> {}
                else -> throw IOException("GitHub answered ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                if (name.startsWith("offreel-") && name.endsWith(".apk")) {
                    return@withContext Release(version, a.getString("browser_download_url"), a.optLong("size", -1))
                }
            }
            null   // release without an APK yet (still building)
        } finally {
            conn.disconnect()
        }
    }

    /** "0.10.0" > "0.9.3": compares the numbers, not the text. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }.let { it + List(3 - it.size) { 0 } }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0..2) if (r[i] != l[i]) return r[i] > l[i]
        return false
    }

    private suspend fun download(context: Context, release: Release): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "offreel-${release.version}.apk")
        if (!(apk.exists() && (release.apkSize <= 0 || apk.length() == release.apkSize))) {
            dir.listFiles()?.forEach { it.delete() }
            val part = File(dir, "download.part")
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "offreel-updater")
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode != 200) throw IOException("download answered ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; _status.value = Status.Downloading(release.version, pct) }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (!part.renameTo(apk)) throw IOException("couldn't keep the download")
        }
        // Make sure it really is a newer offreel before handing it to Android (which also checks the signature).
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info?.packageName != context.packageName || info.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            apk.delete()
            _status.value = Status.Failed("The downloaded file isn't a newer offreel.")
            return@withContext null
        }
        apk
    }

    /** Installs [apk] over this app. On Android 12+ this needs no tap once "install unknown apps" is allowed. */
    fun install(context: Context, apk: File, version: String) {
        _status.value = Status.Installing(version)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        try {
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("offreel.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, UpdateInstallReceiver::class.java).putExtra(UpdateInstallReceiver.EXTRA_VERSION, version),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            _status.value = Status.Failed("Couldn't start the install: ${e.message}")
        }
    }

    /** Android wants the user to confirm: show its dialog if offreel is open, else a notification to tap. */
    fun askUser(context: Context, confirm: Intent, version: String) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appVisible) {
            runCatching { context.startActivity(confirm) }.onSuccess { return }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            _status.value = Status.Available(version)   // will be offered again next time the app is opened
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("offreel $version is ready")
                .setContentText("Tap to install the update")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun canInstallPackages(context: Context) = context.packageManager.canRequestPackageInstalls()
}
