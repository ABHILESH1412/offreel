package dev.offreel.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Everything offreel knows about Instagram's screen. Instagram never exposes a reel's URL in its
 * UI tree, so we press its own Share → Copy link and read the result from the clipboard.
 *
 * Tested against Instagram 455 (Android 16). When Instagram renames a view, fix it here.
 */
class InstagramDriver(private val service: AccessibilityService) {

    companion object {
        const val IG = "com.instagram.android"
        private const val TAG = "offreel.driver"

        private const val REEL_SHARE_ID = "$IG:id/direct_share_button"
        private const val REEL_AUTHOR_ID = "$IG:id/clips_author_username"
        private const val FEED_AUTHOR_ID = "$IG:id/row_feed_photo_profile_name"
        private const val FEED_SHARE_ID = "$IG:id/row_feed_button_share"
        private const val SHARE_ROW_ID = "$IG:id/direct_external_reshare_row"

        /** Label starts; the feed's says e.g. "Send post. 234K shares. Button. Double tap to…". */
        private val SHARE_DESCRIPTION_STARTS = listOf("share", "send post", "send")
        private const val COPY_LINK = "Copy link"
        private const val COPIED = "Copied"
        private const val DISMISS = "Dismiss"
    }

    /** The reel or post on screen: its Share button, and the creator's name when we can see it. */
    class Target(val share: AccessibilityNodeInfo, val author: String?)

    sealed interface CopyOutcome {
        /** Instagram put the link on the clipboard. */
        data object Copied : CopyOutcome
        /** The share sheet has no Copy link (ads). */
        data object NoCopyLink : CopyOutcome
        /** The share sheet closed under us (the user pressed Back) or never opened. */
        data object SheetClosed : CopyOutcome
        /** Instagram is no longer on screen. */
        data object Lost : CopyOutcome
        data object TimedOut : CopyOutcome
    }

    // ---------- which app is on screen ----------

    /** Package of the top app window, ignoring system UI, keyboards and our own overlays. */
    fun topAppPackage(): String? {
        for (w in service.windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = w.root?.packageName ?: continue
            return pkg.toString()
        }
        return null
    }

    /** Instagram has a visible window (possibly under a see-through window of another app). */
    fun instagramVisible(): Boolean = igRoot() != null

    fun igRoot(): AccessibilityNodeInfo? {
        for (w in service.windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            if (r.packageName?.toString() == IG) return r
        }
        return null
    }

    // ---------- finding the reel or post ----------

    fun findTarget(): Target? {
        val root = igRoot() ?: return null
        if (shareSheetOpen(root)) return null

        // Reels viewer (Reels tab, or a reel opened from anywhere).
        pickMostVisible(root.findAccessibilityNodeInfosByViewId(REEL_SHARE_ID))?.let { share ->
            val author = pickMostVisible(root.findAccessibilityNodeInfosByViewId(REEL_AUTHOR_ID))?.text?.toString()
            return Target(share, author?.trim())
        }

        // Feed or a single post: the visible Share button closest to the middle of the screen.
        val screenMid = service.resources.displayMetrics.heightPixels * 0.5f
        val candidates = root.findAccessibilityNodeInfosByViewId(FEED_SHARE_ID).filter { it.isVisibleToUser }.toMutableList()
        if (candidates.isEmpty()) {
            collect(root, candidates) { n ->
                val d = n.contentDescription?.toString()?.trim()?.lowercase()
                d != null && SHARE_DESCRIPTION_STARTS.any { d == it || d.startsWith("$it.") || d.startsWith("$it ") } &&
                    n.isVisibleToUser && n.viewIdResourceName?.contains("tab") != true
            }
        }
        val share = candidates.minByOrNull { abs(bounds(it).exactCenterY() - screenMid) } ?: return null
        val shareTop = bounds(share).top
        // The post's author row sits above its Share button; take the nearest one above it.
        val author = root.findAccessibilityNodeInfosByViewId(FEED_AUTHOR_ID)
            .filter { it.isVisibleToUser && bounds(it).top < shareTop }
            .maxByOrNull { bounds(it).top }
            ?.text?.toString()?.trim()
        return Target(share, author)
    }

    /** For the log when no reel is found: the visible labelled views, so a changed screen can be fixed. */
    fun describeScreen(): String {
        val root = igRoot() ?: return "no Instagram window"
        val out = mutableListOf<AccessibilityNodeInfo>()
        collect(root, out) { it.isVisibleToUser && (it.contentDescription != null || it.viewIdResourceName != null) }
        return out.take(120).joinToString("\n") { n ->
            "${n.viewIdResourceName?.removePrefix("$IG:id/") ?: "-"} | ${n.contentDescription ?: ""} | ${n.text ?: ""} | ${bounds(n).toShortString()}"
        }
    }

    // ---------- Share → Copy link ----------

    /**
     * Opens the share sheet and taps Copy link, then waits until Instagram shows "Copied".
     * Instagram fetches the link from its server, which takes 0.5–10 s.
     */
    suspend fun copyLink(target: Target, onCopyClicked: () -> Unit = {}): CopyOutcome {
        val start = SystemClock.uptimeMillis()
        if (!click(target.share)) Log.w(TAG, "share click not accepted")

        // Phase 1: wait for the sheet to open and finish sliding in (a tap mid-animation is ignored).
        var sheetSeen = false
        var goneSince = 0L
        var lastBounds: Rect? = null
        var stable = 0
        var copy: AccessibilityNodeInfo? = null
        while (copy == null) {
            delay(50)
            val now = SystemClock.uptimeMillis()
            val root = igRoot() ?: return CopyOutcome.Lost
            if (shareSheetOpen(root)) { sheetSeen = true; goneSince = 0 } else {
                if (!sheetSeen && now - start > 1_500) return CopyOutcome.SheetClosed
                if (sheetSeen) {
                    if (goneSince == 0L) goneSince = now else if (now - goneSince > 300) return CopyOutcome.SheetClosed
                }
            }
            val node = findCopyLink(root)
            if (node == null) {
                if (sheetSeen && now - start > 3_500) return CopyOutcome.NoCopyLink
                continue
            }
            val b = bounds(node)
            if (b == lastBounds) stable++ else { stable = 0; lastBounds = b }
            if (stable >= 2) copy = node
        }

        // Phase 2: tap Copy link and wait for "Copied". One retry if Instagram ignored the tap.
        click(copy)
        onCopyClicked()
        val clickedAt = SystemClock.uptimeMillis()
        var retried = false
        goneSince = 0
        while (SystemClock.uptimeMillis() - clickedAt < 10_000) {
            delay(50)
            val now = SystemClock.uptimeMillis()
            val root = igRoot() ?: return CopyOutcome.Lost
            if (findByDescOrText(root, COPIED) != null) return CopyOutcome.Copied
            if (!shareSheetOpen(root)) {
                if (goneSince == 0L) goneSince = now else if (now - goneSince > 300) return CopyOutcome.SheetClosed
            } else goneSince = 0
            if (!retried && now - clickedAt > 4_000) {
                retried = true
                findCopyLink(root)?.let { Log.i(TAG, "retrying Copy link"); click(it) }
            }
        }
        return CopyOutcome.TimedOut
    }

    fun shareSheetOpen(root: AccessibilityNodeInfo?): Boolean =
        root != null && (findByDesc(root, DISMISS) != null || findByDesc(root, COPY_LINK) != null ||
            findByDescOrText(root, COPIED) != null)

    /** Closes Instagram's share sheet if it is open. Returns true when no sheet is left open. */
    fun dismissShareSheet(): Boolean {
        val root = igRoot() ?: return false
        if (!shareSheetOpen(root)) return true
        findByDesc(root, DISMISS)?.let { click(it) }
        return false
    }

    /** Closes a sheet we left open (e.g. the user left Instagram mid-save), checking that it closed. */
    suspend fun closeLeftoverSheet(settleMs: Long = 300) {
        delay(settleMs)   // after returning to Instagram, let it finish restoring its screen
        repeat(3) {
            if (dismissShareSheet()) return
            delay(400)
        }
        if (shareSheetOpen(igRoot())) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun findCopyLink(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByDesc(root, COPY_LINK)?.let { return it }
        // Copy link can be scrolled off the end of the share row.
        root.findAccessibilityNodeInfosByViewId(SHARE_ROW_ID).firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return null
    }

    // ---------- node helpers ----------

    /** Clicks the node or, if it refuses, the nearest ancestor that accepts the click. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        repeat(5) {
            val cur = n ?: return false
            if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = cur.parent
        }
        return false
    }

    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    private fun pickMostVisible(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        nodes.filter { it.isVisibleToUser }.maxByOrNull { val r = bounds(it); r.width().coerceAtLeast(0) * r.height().coerceAtLeast(0) }

    private fun findByDesc(n: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (n.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true) return n
        for (i in 0 until n.childCount) {
            val found = n.getChild(i)?.let { findByDesc(it, desc) }
            if (found != null) return found
        }
        return null
    }

    private fun findByDescOrText(n: AccessibilityNodeInfo, s: String): AccessibilityNodeInfo? {
        if (n.contentDescription?.toString()?.equals(s, ignoreCase = true) == true ||
            n.text?.toString()?.equals(s, ignoreCase = true) == true
        ) return n
        for (i in 0 until n.childCount) {
            val found = n.getChild(i)?.let { findByDescOrText(it, s) }
            if (found != null) return found
        }
        return null
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, match: (AccessibilityNodeInfo) -> Boolean) {
        if (match(n)) out += n
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, match) }
    }
}
