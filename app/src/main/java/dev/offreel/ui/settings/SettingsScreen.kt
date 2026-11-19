package dev.offreel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yausername.youtubedl_android.YoutubeDL
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.offreel.update.UpdateWorker
import dev.offreel.update.Updates
import dev.offreel.BuildConfig
import dev.offreel.data.Quality
import dev.offreel.download.Ytdl
import dev.offreel.graph
import dev.offreel.ui.common.formatBytes
import dev.offreel.ui.common.isCaptureServiceEnabled
import dev.offreel.ui.common.openAccessibilitySettings
import dev.offreel.ui.theme.LocalOffreelColors
import dev.offreel.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = context.graph
    val store = graph.settings
    val scope = rememberCoroutineScope()
    val s by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val storage by graph.repository.storageBytes.collectAsStateWithLifecycle(initialValue = 0L)
    val failed by graph.repository.failedCount.collectAsStateWithLifecycle(initialValue = 0)
    var serviceOn by remember { mutableStateOf(isCaptureServiceEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        serviceOn = isCaptureServiceEnabled(context)
        onPauseOrDispose { }
    }
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { ytdlpVersion = withContext(Dispatchers.IO) { Ytdl.version(context) } }

    val settings = s ?: return
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp))

        Section("Appearance") {
            Choice(
                options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light"),
                selected = settings.theme,
                onSelect = { scope.launch { store.setTheme(it) } },
            )
        }

        Section("Downloads") {
            Label("Video quality")
            Choice(
                options = Quality.entries.map { it to it.label },
                selected = settings.quality,
                onSelect = { scope.launch { store.setQuality(it) } },
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "Also save to gallery",
                subtitle = "Copy each new reel to Movies/offreel, where your gallery can see it",
                checked = settings.autoGallery, onChange = { scope.launch { store.setAutoGallery(it) } },
            )
            SwitchRow(
                title = "Wi-Fi only", subtitle = "Wait for Wi-Fi before downloading saved reels",
                checked = settings.wifiOnly, onChange = { scope.launch { store.setWifiOnly(it) } },
            )
        }

        Section("Save button") {
            SwitchRow(
                title = "Show over Instagram", subtitle = "The round offreel button, only while Instagram is open",
                checked = settings.buttonEnabled, onChange = { scope.launch { store.setButtonEnabled(it) } },
            )
            ActionRow(
                title = "Accessibility access",
                subtitle = if (serviceOn) "On" else "Off: the button can't appear until this is on",
                action = if (serviceOn) "Manage" else "Turn on",
                onClick = { openAccessibilitySettings(context) },
            )
            ActionRow(
                title = "Button position", subtitle = "Drag the button to move it; this puts it back",
                action = "Reset", onClick = { scope.launch { store.resetButtonPosition() } },
            )
        }

        Section("Updates") {
            UpdatesSection(autoUpdate = settings.autoUpdate, onAutoUpdate = { on ->
                scope.launch {
                    store.setAutoUpdate(on)
                    UpdateWorker.schedule(context, store.current())
                }
            })
        }

        Section("Downloader") {
            ActionRow(
                title = "yt-dlp ${ytdlpVersion ?: "…"}",
                subtitle = updateStatus ?: "Updates itself every few days. Update now if downloads start failing.",
                action = "Update",
                onClick = {
                    updateStatus = "Updating…"
                    scope.launch {
                        val r = Ytdl.update(context, store)
                        updateStatus = when (r) {
                            YoutubeDL.UpdateStatus.DONE -> "Updated"
                            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date"
                            null -> "Update failed. Check your connection."
                        }
                        ytdlpVersion = withContext(Dispatchers.IO) { Ytdl.version(context) }
                    }
                },
            )
        }

        Section("Storage") {
            ActionRow(
                title = "Saved videos",
                subtitle = "${formatBytes(storage)} in offreel's private storage. Other apps and the gallery can't see " +
                    "these, and uninstalling offreel deletes them. Use Save to gallery to keep a copy in Movies/offreel.",
            )
            if (failed > 0) {
                ActionRow(
                    title = "$failed failed ${if (failed == 1) "download" else "downloads"}",
                    subtitle = "Try them all again",
                    action = "Retry",
                    onClick = { scope.launch { graph.repository.retryAllFailed() } },
                )
            }
        }

        Text(
            "offreel ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall, color = LocalOffreelColors.current.muted,
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelMedium,
            color = LocalOffreelColors.current.muted, modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = LocalOffreelColors.current.line)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val extra = LocalOffreelColors.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    activeContentColor = MaterialTheme.colorScheme.onSurface,
                    activeBorderColor = extra.line,
                    inactiveBorderColor = extra.line,
                ),
            ) { Text(label) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val extra = LocalOffreelColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = extra.accent, checkedThumbColor = extra.onAccent),
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, action: String? = null, onClick: () -> Unit = {}) {
    val extra = LocalOffreelColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
        if (action != null) OutlinedButton(onClick = onClick) { Text(action, color = MaterialTheme.colorScheme.onSurface) }
    }
}


@Composable
private fun UpdatesSection(autoUpdate: Boolean, onAutoUpdate: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Updates.status.collectAsStateWithLifecycle()
    var canInstall by remember { mutableStateOf(Updates.canInstallPackages(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = Updates.canInstallPackages(context)
        onPauseOrDispose { }
    }
    // "Update ready, tap to install" needs notification permission on Android 13+.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    SwitchRow(
        title = "Automatic updates",
        subtitle = "Checks GitHub about twice a day and installs new versions of offreel",
        checked = autoUpdate,
        onChange = { on ->
            onAutoUpdate(on)
            if (on && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
    )
    if (Updates.canInstall && !canInstall) {
        ActionRow(
            title = "Allow offreel to install updates",
            subtitle = "Needed once. Android then installs offreel updates without asking (Android 12+).",
            action = "Allow",
            onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }
    val busy = status is Updates.Status.Checking || status is Updates.Status.Downloading || status is Updates.Status.Installing
    ActionRow(
        title = "Version ${BuildConfig.VERSION_NAME}",
        subtitle = when (val s = status) {
            Updates.Status.Idle -> if (Updates.canInstall) "From github.com/${Updates.REPO}" else
                "Development build: updates apply to the app installed from GitHub releases"
            Updates.Status.Checking -> "Checking…"
            is Updates.Status.UpToDate -> "Up to date"
            is Updates.Status.Available -> if (Updates.canInstall) "Version ${s.version} is ready. Tap Check now to install it" else
                "Version ${s.version} is on GitHub (install it from the release page)"
            is Updates.Status.Downloading -> "Downloading ${s.version}… ${s.percent}%"
            is Updates.Status.Installing -> "Installing ${s.version}…"
            is Updates.Status.Failed -> s.message
        },
        action = if (busy) null else "Check now",
        onClick = { scope.launch { Updates.checkAndInstall(context) } },
    )
}
