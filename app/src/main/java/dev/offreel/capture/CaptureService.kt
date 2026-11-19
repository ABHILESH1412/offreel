package dev.offreel.capture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.offreel.capture.InstagramDriver.CopyOutcome
import dev.offreel.data.GroupEntity
import dev.offreel.data.LinkParser
import dev.offreel.data.Settings
import dev.offreel.graph
import dev.offreel.ui.save.LinkState
import dev.offreel.ui.save.SaveSheet
import dev.offreel.ui.save.SaveUiState
import dev.offreel.ui.theme.OffreelTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.graphics.Bitmap
import android.view.Display
import android.view.Gravity
import android.view.View
import android.graphics.PixelFormat
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.coroutines.resume

/**
 * offreel's accessibility service. It only watches which app is on screen; while that is
 * Instagram it shows the save button, and nothing else. Tapping the button opens the "Save to…"
 * sheet and, behind it, presses Instagram's Share → Copy link to learn the reel's URL.
 */
class CaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "offreel.capture"
    }

    private val scope = MainScope()
    private lateinit var driver: InstagramDriver
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    private var settings = Settings()
    private var inInstagram = false
    private var button: FloatingButton? = null
    private var capture: Capture? = null
    /** We left Instagram's share sheet open (the user left mid-save); close it on return. */
    private var leftoverSheet = false
    private var recheck: Job? = null

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { capture?.leftInstagram("screen off") }
    }

    override fun onServiceConnected() {
        driver = InstagramDriver(this)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
        scope.launch {
            graph.settings.settings.collect {
                settings = it
                updateButton()
            }
        }
        onWindowsChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> onWindowsChanged()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        capture?.leftInstagram("service stopping")
        removeButton()
        runCatching { unregisterReceiver(screenOff) }
        scope.cancel()
    }

    // ---------- requirement: only active while Instagram is on screen ----------

    /**
     * "Instagram is open" means Instagram's window is visible, not that it is the top window:
     * some apps put a see-through window on top for a moment (clipboard-sync apps do this the
     * instant Instagram copies a link). Going Home or switching apps hides Instagram's window.
     */
    private fun onWindowsChanged() {
        if (!::driver.isInitialized) return
        recheck?.cancel()
        val visible = driver.instagramVisible()
        val top = driver.topAppPackage()
        if (capture != null) Log.d(TAG, "windows changed during save: top=$top instagramVisible=$visible")
        if (visible) {
            applyInstagramVisible(true)
            return
        }
        val confirmAfter = when {
            !inInstagram -> 0L
            top != null && top in homePackages -> 0L    // Home or Recents: the user left
            top == null -> 300L                         // a transition frame
            else -> 1_500L                              // another app's window; may be a brief pop-up
        }
        recheck = scope.launch {
            delay(confirmAfter)
            applyInstagramVisible(driver.instagramVisible())
        }
    }

    /** The launcher (Home and Recents) and system UI. */
    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet() + "com.android.systemui"
    }

    private fun applyInstagramVisible(nowInInstagram: Boolean) {
        if (!nowInInstagram) capture?.leftInstagram("top: ${driver.topAppPackage()}")
        if (nowInInstagram && !inInstagram && leftoverSheet) {
            leftoverSheet = false
            scope.launch { driver.closeLeftoverSheet() }
        }
        inInstagram = nowInInstagram
        updateButton()
    }

    private fun updateButton() {
        val want = inInstagram && settings.buttonEnabled
        val b = button
        when {
            want && b == null -> {
                val nb = FloatingButton(
                    this, wm,
                    onTap = { startCapture(quickGroupId = null) },
                    onLongPress = { startCapture(quickGroupId = settings.lastGroupId) },
                    onMoved = { y, left -> scope.launch { graph.settings.setButtonPosition(y, left) } },
                )
                nb.place(settings.buttonY, settings.buttonOnLeft)
                runCatching { wm.addView(nb, nb.params) }.onSuccess { button = nb }
            }
            !want && b != null -> removeButton()
            b != null && !b.dragging -> b.place(settings.buttonY, settings.buttonOnLeft)
        }
    }

    private fun removeButton() {
        button?.let { runCatching { wm.removeViewImmediate(it) } }
        button = null
    }

    // ---------- requirement: one tap saves the reel on screen ----------

    private var starting = false

    private fun startCapture(quickGroupId: Long?) {
        if (capture != null || starting) return
        val target = driver.findTarget()
        if (target == null) {
            Log.i(TAG, "no reel or post found on screen:\n${driver.describeScreen()}")
            Toast.makeText(this, "Open a reel or post first", Toast.LENGTH_SHORT).show()
            return
        }
        starting = true
        val tapAt = SystemClock.uptimeMillis()
        scope.launch {
            // A still of the reel, taken before Instagram's share sheet opens, becomes the cover:
            // the user sees the reel (dimmed under our sheet), never a black screen or Instagram's sheet.
            val still = takeStill()
            Log.d(TAG, "timing: still ${if (still != null) "taken" else "missing"} after ${SystemClock.uptimeMillis() - tapAt} ms")
            starting = false
            if (capture == null) capture = Capture(target, quickGroupId, still).also { it.start() }
            Log.d(TAG, "timing: overlay added after ${SystemClock.uptimeMillis() - tapAt} ms")
        }
    }

    /** One frame of the screen (Android 11+), or null. Takes ~50 ms. */
    private suspend fun takeStill(): ImageBitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return withTimeoutOrNull(400) {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hw = result.hardwareBuffer
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        hw.close()
                        if (cont.isActive) cont.resume(bitmap?.asImageBitmap())
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }
    }

    /**
     * One save: the sheet on top, the link grab underneath. As soon as the link is known the reel is
     * saved (to Default, or the group tapped meanwhile) and its download queued, so closing the sheet
     * never loses it. Only leaving Instagram before the link arrives means nothing can be saved.
     */
    private inner class Capture(
        private var target: InstagramDriver.Target,
        private val quickGroupId: Long?,
        private val still: ImageBitmap?,
    ) {
        val state = SaveUiState().apply { author = target.author }
        private val startedAt = System.currentTimeMillis()
        private var grabJob: Job? = null
        private var autoClose: Job? = null
        private var sheetMayBeOpen = false
        /** Closing Instagram's share sheet; the cover must stay up until this is done. */
        private var sheetClosing: Job? = null
        private var autoRetried = false
        private var finished = false
        /** When Instagram's Copy link was tapped (uptime ms), 0 before that. */
        private var copyClickedAt = 0L
        /** The user closed the sheet early: give the screen back and finish the save out of sight. */
        private var handoff: Job? = null
        private val repo get() = graph.repository

        private val overlay = OverlayWindow(this@CaptureService, onBack = { dismiss("back") }) {
            val groups: List<GroupEntity>? by repo.groups.collectAsState(initial = null)
            OffreelTheme(settings.theme) {
                SaveSheet(
                    state = state,
                    backdrop = still,
                    groups = groups,
                    lastGroupId = settings.lastGroupId,
                    onPick = ::pick,
                    onCreate = ::create,
                    onRetry = ::retry,
                    onDismiss = { dismiss("closed") },
                )
            }
        }

        fun start() {
            overlay.show()
            if (quickGroupId != null) {
                // Long-press: straight into the last group used, if it still exists.
                scope.launch { if (graph.db.dao().groupName(quickGroupId) != null) state.pendingGroupId = quickGroupId }
            }
            grab()
        }

        private fun grab() {
            state.link = LinkState.Loading
            sheetMayBeOpen = true
            copyClickedAt = 0L
            grabJob = scope.launch {
                val outcome = withTimeoutOrNull(16_000) {
                    driver.copyLink(target) { copyClickedAt = SystemClock.uptimeMillis() }
                } ?: CopyOutcome.TimedOut
                Log.i(TAG, "copy link: $outcome")
                when (outcome) {
                    CopyOutcome.Copied -> {
                        // Reading the clipboard needs focus; Instagram has finished copying, so taking it is safe now.
                        overlay.setFocusable(true)
                        val text = readClipboard()
                        Log.d(TAG, "clipboard read: ${if (text != null) "link found" else "nothing"}")
                        // Hand focus back at once: clipboard-watching apps (sync tools) open a window
                        // that waits for focus before it can close. Take it again once Instagram is back.
                        overlay.setFocusable(false)
                        closeInstagramSheet()
                        regainFocusSoon()
                        state.canType = true
                        val link = LinkParser.parse(text)
                        if (link != null) {
                            state.link = LinkState.Ready(link)
                            save()
                        } else {
                            fail("Couldn't read the link", canRetry = true)
                        }
                    }
                    CopyOutcome.NoCopyLink -> {
                        closeInstagramSheet()
                        fail("This one has no link to save (ads can't be saved)", canRetry = false)
                    }
                    CopyOutcome.TimedOut -> {
                        closeInstagramSheet()
                        fail("Instagram took too long", canRetry = true)
                    }
                    // Back reached Instagram (the sheet isn't focusable yet) and closed its share sheet.
                    // The user meant "close", not "don't save": try once more quietly.
                    CopyOutcome.SheetClosed -> {
                        sheetMayBeOpen = false
                        if (!autoRetried) {
                            autoRetried = true
                            state.closing = true
                            retry()
                        } else {
                            fail("Couldn't get the link", canRetry = true)
                        }
                    }
                    CopyOutcome.Lost -> leftInstagram("Instagram gone")
                }
            }
        }

        private fun fail(message: String, canRetry: Boolean) {
            state.link = LinkState.Failed(message, canRetry)
            if (state.closing) {
                toast("Not saved: $message")
                finishWhenSheetClosed()
            }
        }

        /** Remove the cover only after Instagram's share sheet has gone, so it never flashes into view. */
        private fun finishWhenSheetClosed() {
            scope.launch {
                withTimeoutOrNull(2_000) { sheetClosing?.join() }
                delay(250)   // Instagram's slide-down animation
                finish()
            }
        }

        /** Save as soon as the link is known: to the group tapped meanwhile, else to Default. */
        private fun save() {
            val link = (state.link as? LinkState.Ready)?.link ?: return
            scope.launch {
                val picked = state.pendingGroupId
                val result = runCatching { repo.save(link, picked, state.author) }.getOrElse {
                    Log.e(TAG, "save failed", it)
                    toast("Couldn't save this one")
                    finish()
                    return@launch
                }
                state.saved = result
                if (picked != null) graph.settings.setLastGroup(picked)
                when {
                    state.closing -> { toast(result.message()); finishWhenSheetClosed() }
                    picked != null -> { state.doneMessage = result.message(); delay(900); finish() }
                    else -> autoClose = scope.launch {
                        // Saved to Default; give the user a few seconds to move it, then get out of the way.
                        delay(6_000)
                        if (!state.keepOpen && state.movingTo == null) finish()
                    }
                }
            }
        }

        fun pick(groupId: Long) {
            if (finished || state.movingTo != null) return
            val saved = state.saved
            if (saved == null) {
                // Link still loading: remember the choice and save straight there when it arrives.
                if (state.link == LinkState.Loading && state.pendingGroupId == null) state.pendingGroupId = groupId
                return
            }
            autoClose?.cancel()
            state.movingTo = groupId
            scope.launch {
                // A reel saved before stays in its groups; a new one moves out of Default.
                val name = repo.moveToGroup(saved.reelId, if (saved.existed) null else saved.groupId, groupId)
                graph.settings.setLastGroup(groupId)
                state.doneMessage = if (saved.existed) "Added to $name" else "Moved to $name"
                delay(900)
                finish()
            }
        }

        fun create(name: String) {
            if (finished || state.movingTo != null) return
            scope.launch { pick(repo.createGroup(name)) }
        }

        /** Closing the sheet (tap outside, Back, Done). A captured reel stays saved. */
        fun dismiss(reason: String) {
            if (finished) return
            Log.i(TAG, "sheet closed: $reason")
            if (state.link == LinkState.Loading) {
                state.closing = true
                handBackScreen()
            } else {
                finishWhenSheetClosed()
            }
        }

        /** Instagram is no longer on screen. Before the link arrives, nothing can be saved. */
        fun leftInstagram(reason: String) {
            if (finished) return
            Log.i(TAG, "left Instagram during save: $reason")
            if (state.link == LinkState.Loading) {
                grabJob?.cancel()
                toast("Not saved: Instagram closed before the link was ready")
                if (sheetMayBeOpen) leftoverSheet = true
            }
            finish()
        }

        /** Focus is what routes Back and typing to the sheet. */
        private fun regainFocusSoon() {
            scope.launch {
                val waited = withTimeoutOrNull(4_000) {
                    while (driver.topAppPackage() != InstagramDriver.IG) delay(50)
                } != null
                delay(150)
                Log.d(TAG, "regaining focus (Instagram back on top: $waited)")
                if (!finished) overlay.setFocusable(true)
            }
        }

        private fun closeInstagramSheet() {
            sheetMayBeOpen = false
            sheetClosing = scope.launch { driver.closeLeftoverSheet(settleMs = 0) }
        }

        private suspend fun readClipboard(): String? {
            repeat(40) {
                delay(50)
                readFreshLink()?.let { return it }
            }
            return null
        }

        /** An Instagram link copied since this save started, or null. Needs window focus to work. */
        private fun readFreshLink(): String? {
            val clip = runCatching { getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull() ?: return null
            val ts = clip.description?.timestamp ?: 0
            val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return null
            return text.takeIf { ts >= startedAt - 500 && LinkParser.parse(it) != null }
        }

        /**
         * The user closed the sheet while the link was loading. Closing Instagram's share sheet right
         * after Copy link is tapped cancels the copy, but half a second later it doesn't (tested on
         * Instagram 455). So: wait for that point, close Instagram's sheet, remove our cover so the
         * user has the screen back, and pick the link up from the clipboard in the background.
         */
        private fun handBackScreen() {
            if (handoff != null) return
            handoff = scope.launch {
                while (state.link == LinkState.Loading &&
                    (copyClickedAt == 0L || SystemClock.uptimeMillis() - copyClickedAt < 500)
                ) delay(50)
                if (state.link != LinkState.Loading || finished) return@launch   // the normal path finished first
                Log.i(TAG, "handing the screen back; finishing the save in the background")
                grabJob?.cancel()
                closeInstagramSheet()
                withTimeoutOrNull(1_500) { sheetClosing?.join() }
                delay(250)
                val picked = state.pendingGroupId
                val author = state.author
                finish()   // cover gone, button back; the rest happens out of sight

                val link = LinkParser.parse(readClipboardInBackground(10_000))
                if (link == null) {
                    Log.i(TAG, "background save: no link arrived")
                    toast("Not saved: Instagram didn't hand over the link. Try again.")
                    return@launch
                }
                val result = runCatching { repo.save(link, picked, author) }.getOrNull()
                if (result != null && picked != null) graph.settings.setLastGroup(picked)
                Log.i(TAG, "background save: ${result?.message()}")
                toast(result?.message() ?: "Couldn't save this one")
            }
        }

        /** Polls the clipboard, holding focus only ~80 ms per look so the user's app keeps working. */
        private suspend fun readClipboardInBackground(timeoutMs: Long): String? {
            val end = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < end) {
                withBriefFocus { readFreshLink() }?.let { return it }
                delay(350)
            }
            return null
        }

        /** Adds an invisible 1x1 window that takes focus (needed to read the clipboard), runs [block], removes it. */
        private suspend fun <T> withBriefFocus(block: () -> T): T {
            val probe = View(this@CaptureService)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSPARENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            return try {
                wm.addView(probe, lp)
                delay(80)
                block()
            } finally {
                runCatching { wm.removeViewImmediate(probe) }
            }
        }

        fun retry() {
            if (finished || grabJob?.isActive == true && state.link == LinkState.Loading && !state.closing) return
            overlay.setFocusable(false)   // focus now would make Instagram close the sheet mid-copy
            state.canType = false
            target = driver.findTarget() ?: run {
                fail("The reel isn't on screen any more", canRetry = false)
                return
            }
            grab()
        }

        private fun finish() {
            if (finished) return
            finished = true
            autoClose?.cancel()
            overlay.dismiss()
            if (capture === this) capture = null
            updateButton()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
