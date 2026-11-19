package dev.offreel.probe;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.KeyEvent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/**
 * Throwaway probe for offreel. Shows a button only while Instagram is the top app.
 * Tap: cover screen (optional), click Share -> Copy link via accessibility, read clipboard, log result.
 * Long-press the button: toggle the full-screen cover on/off.
 * All output goes to logcat tag OFFREEL_PROBE.
 */
public class ProbeService extends AccessibilityService {
    static final String IG = "com.instagram.android";
    static final String TAG = "OFFREEL_PROBE";

    final Handler h = new Handler(Looper.getMainLooper());
    WindowManager wm;
    TextView button;
    FrameLayout mask;
    boolean useMask = true;
    boolean busy;
    int run;
    long t0, wallT0, elapsedT0;
    String author;
    StringBuilder steps;

    interface Finder { AccessibilityNodeInfo find(); }

    static final long WATCHDOG_MS = 15000;   // the cover can never stay longer than this
    boolean sheetMayBeOpen;                  // we left Instagram with its share sheet open

    final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { abort("screen-off"); }
    };

    @Override protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, f);
        Log.i(TAG, "service connected");
        updateButton();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (busy) checkStillInInstagram();
            else updateButton();
        }
    }

    /** While saving: the moment another app takes the screen, cancel and remove the cover. */
    void checkStillInInstagram() {
        String top = topAppPackage();
        if (IG.equals(top)) return;
        if (top != null) { abort("left-instagram top=" + top); return; }
        // No app window can be a single transition frame; confirm shortly after.
        h.postDelayed(() -> { if (busy && !IG.equals(topAppPackage())) abort("left-instagram (no app window)"); }, 250);
    }

    @Override public void onInterrupt() { abort("interrupted"); }

    @Override public boolean onUnbind(Intent intent) {
        abort("unbind");
        removeAllOverlays();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        abort("destroy");
        removeAllOverlays();
        try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
        super.onDestroy();
    }

    void removeAllOverlays() {
        hideMask();
        if (button != null) { try { wm.removeViewImmediate(button); } catch (Exception ignored) {} button = null; }
    }

    // ---------- requirement 1: only show while Instagram is on screen ----------

    String topAppPackage() {
        List<AccessibilityWindowInfo> ws = getWindows();
        for (AccessibilityWindowInfo w : ws) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            return r.getPackageName().toString();
        }
        return null;
    }

    AccessibilityNodeInfo igRoot() {
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null && IG.contentEquals(r.getPackageName())) return r;
        }
        return null;
    }

    void updateButton() {
        if (busy) return;
        String top = topAppPackage();
        boolean ig = IG.equals(top);
        if (ig && button == null) showButton(top);
        else if (!ig && button != null) hideButton(top);
        if (ig && sheetMayBeOpen) {
            // Back in Instagram after a cancelled save: close the share sheet we left open,
            // after Instagram has finished restoring, and verify it really closed.
            sheetMayBeOpen = false;
            h.postDelayed(() -> closeLeftoverSheet(1), 300);
        }
    }

    boolean shareSheetOpen(AccessibilityNodeInfo r) {
        return r != null && (findByDesc(r, "Dismiss", false) != null || findByDesc(r, "Copy link", false) != null
                || findByDescOrText(r, "Copied") != null);
    }

    void closeLeftoverSheet(int attempt) {
        if (busy) return;                       // a new save started: don't touch its sheet
        AccessibilityNodeInfo r = igRoot();
        if (r == null) { sheetMayBeOpen = true; return; }   // left again; retry on next return
        if (!shareSheetOpen(r)) { Log.i(TAG, "leftover sheet: CLOSED (attempt " + attempt + ")"); return; }
        if (attempt > 3) {
            Log.i(TAG, "leftover sheet: still open after Dismiss x3, pressing Back=" + performGlobalAction(GLOBAL_ACTION_BACK));
            h.postDelayed(() -> {
                AccessibilityNodeInfo r2 = igRoot();
                Log.i(TAG, "leftover sheet: " + (shareSheetOpen(r2) ? "STILL OPEN" : "CLOSED") + " after Back");
            }, 500);
            return;
        }
        AccessibilityNodeInfo d = findByDesc(r, "Dismiss", false);
        Log.i(TAG, "leftover sheet: open, Dismiss click=" + (d != null && click(d)) + " (attempt " + attempt + ")");
        h.postDelayed(() -> closeLeftoverSheet(attempt + 1), 400);
    }

    /** False (and the save is cancelled) if the share sheet never opened or was closed under us, e.g. by Back. */
    long shareClickAt;
    boolean sheetSeen;

    boolean sheetStillThere(AccessibilityNodeInfo r) {
        long now = SystemClock.uptimeMillis();
        if (shareSheetOpen(r)) { sheetSeen = true; sheetGoneSince = 0; return true; }
        if (!sheetSeen) {
            if (now - shareClickAt > 1500) { abort("share-sheet-never-opened"); return false; }
            return true;
        }
        if (sheetGoneSince == 0) sheetGoneSince = now;
        else if (now - sheetGoneSince > 300) { abort("share-sheet-closed"); return false; }
        return true;
    }

    void showButton(String top) {
        button = new TextView(this);
        button.setText("⬇");
        button.setTextSize(22);
        button.setTextColor(0xFFF4EFE6);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC181A20);
        bg.setStroke(4, 0xFFFF5A36);
        button.setBackground(bg);
        button.setOnClickListener(v -> extract());
        button.setOnLongClickListener(v -> {
            useMask = !useMask;
            Log.i(TAG, "mask mode = " + useMask);
            return true;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                140, 140,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 40;
        lp.y = 1250;
        wm.addView(button, lp);
        Log.i(TAG, "button SHOWN (top=" + top + ")");
    }

    void hideButton(String top) {
        try { wm.removeViewImmediate(button); } catch (Exception ignored) {}
        button = null;
        Log.i(TAG, "button HIDDEN (top=" + top + ")");
    }

    // ---------- requirement 2: get the current reel's link ----------

    void step(String s) {
        steps.append(SystemClock.uptimeMillis() - t0).append("ms:").append(s).append(" | ");
    }

    void extract() {
        if (busy) return;
        h.removeCallbacksAndMessages(null);    // drop any pending leftover-sheet cleanup
        busy = true;
        run++;
        sheetSeen = false;
        sheetGoneSince = 0;
        t0 = SystemClock.uptimeMillis();
        wallT0 = System.currentTimeMillis();
        elapsedT0 = SystemClock.elapsedRealtime();
        steps = new StringBuilder();
        author = null;
        stableCount = 0;
        lastCopyBounds.setEmpty();
        h.postDelayed(() -> abort("watchdog " + WATCHDOG_MS + "ms"), WATCHDOG_MS);
        try {
            extractSteps();
        } catch (Exception e) {
            abort("error " + e);
        }
    }

    void extractSteps() {
        step("unfocusedRead=" + readClip());

        AccessibilityNodeInfo root = igRoot();
        if (root == null) { finish("FAIL no-ig-root", null); return; }

        AccessibilityNodeInfo share = pickVisible(root.findAccessibilityNodeInfosByViewId(IG + ":id/direct_share_button"));
        String via = "id";
        if (share == null) { share = findByDesc(root, "Share", true); via = "desc"; }
        AccessibilityNodeInfo au = pickVisible(root.findAccessibilityNodeInfosByViewId(IG + ":id/clips_author_username"));
        if (au != null) author = String.valueOf(au.getText());
        if (share == null) { finish("FAIL no-share-button", null); return; }

        if (useMask) showMask();
        shareClickAt = SystemClock.uptimeMillis();
        step("shareClick(" + via + ")=" + click(share));

        waitFor(this::findSettledCopyLink, 3000, copy -> {
            step("copyClick=" + click(copy));

            // Wait until Instagram shows "Copied" before taking focus, so the sheet isn't closed mid-copy.
            waitFor(() -> {
                AccessibilityNodeInfo r = igRoot();
                if (r == null) return null;
                AccessibilityNodeInfo copied = findByDescOrText(r, "Copied");
                if (copied != null) return copied;
                sheetStillThere(r);   // cancels if the user closed the sheet (e.g. Back)
                return null;
            }, 10000, copied -> {
                step("copiedShown");
                focusMask();
                pollClip(1500);
            }, () -> {
                AccessibilityNodeInfo r = igRoot();
                StringBuilder row = new StringBuilder();
                if (r != null) for (AccessibilityNodeInfo l : r.findAccessibilityNodeInfosByViewId(IG + ":id/label"))
                    row.append(l.getContentDescription()).append('/').append(l.getText()).append(", ");
                step("copiedNeverShown row=[" + row + "]");
                focusMask();
                pollClip(1500);
            });
        }, () -> finish("FAIL copy-link-not-found", null));
    }

    void focusMask() {
        if (mask == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mask.getLayoutParams();
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wm.updateViewLayout(mask, lp);
        mask.requestFocus();
    }

    // Only return Copy link once its on-screen position has stopped changing (sheet finished sliding in).
    final Rect lastCopyBounds = new Rect();
    int stableCount;
    long sheetGoneSince;

    AccessibilityNodeInfo findSettledCopyLink() {
        AccessibilityNodeInfo root = igRoot();
        if (root != null && !sheetStillThere(root)) return null;
        AccessibilityNodeInfo n = findCopyLink();
        if (n == null) { stableCount = 0; return null; }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.equals(lastCopyBounds)) stableCount++; else { stableCount = 0; lastCopyBounds.set(r); }
        return stableCount >= 2 ? n : null;
    }

    AccessibilityNodeInfo findCopyLink() {
        AccessibilityNodeInfo root = igRoot();
        if (root == null) return null;
        AccessibilityNodeInfo n = findByDesc(root, "Copy link", false);
        if (n == null) {
            List<AccessibilityNodeInfo> rows = root.findAccessibilityNodeInfosByViewId(IG + ":id/direct_external_reshare_row");
            if (!rows.isEmpty()) rows.get(0).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        return n;
    }

    void pollClip(long timeout) {
        long start = SystemClock.uptimeMillis();
        h.post(new Runnable() {
            @Override public void run() {
                if (!busy) return;
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                String text = null;
                long ts = -1;
                try {
                    ClipData c = cm.getPrimaryClip();
                    if (c != null && c.getItemCount() > 0) {
                        CharSequence cs = c.getItemAt(0).getText();
                        text = cs == null ? null : cs.toString();
                        ts = c.getDescription().getTimestamp();
                    }
                } catch (Exception e) {
                    text = "ERR " + e;
                }
                boolean fresh = ts >= wallT0 || (ts >= elapsedT0 && ts < wallT0 / 2);
                if (text != null && text.contains("instagram.com") && fresh) {
                    step("clipRead(maskFocus=" + (mask != null && mask.hasWindowFocus()) + ",ts=" + ts + ")");
                    finish("OK", text);
                    return;
                }
                if (SystemClock.uptimeMillis() - start > timeout) {
                    step("lastClip=" + text + " ts=" + ts + " maskFocus=" + (mask != null && mask.hasWindowFocus()));
                    finish("FAIL clipboard-unreadable", null);
                    return;
                }
                h.postDelayed(this, 50);
            }
        });
    }

    String readClip() {
        try {
            ClipData c = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (c == null) return "null";
            CharSequence cs = c.getItemAt(0).getText();
            return cs == null ? "nontext" : (cs.length() > 60 ? cs.subSequence(0, 60) + "..." : cs.toString());
        } catch (Exception e) {
            return "ERR " + e;
        }
    }

    /** Cancel a save in progress: stop every pending step and remove the cover right now. */
    void abort(String reason) {
        if (!busy) return;
        h.removeCallbacksAndMessages(null);
        hideMask();
        busy = false;
        sheetMayBeOpen = true;
        Log.i(TAG, "RESULT run=" + run + " status=ABORT " + reason.replace(' ', '_') + " author=" + author + " url=null total="
                + (SystemClock.uptimeMillis() - t0) + "ms mask=" + useMask + " steps=" + steps);
        try { updateButton(); } catch (Exception ignored) {}
    }

    void finish(String status, String url) {
        if (!busy) return;
        h.removeCallbacksAndMessages(null);   // also cancels the watchdog
        AccessibilityNodeInfo root = igRoot();
        if (root != null) {
            AccessibilityNodeInfo dismiss = findByDesc(root, "Dismiss", false);
            if (dismiss != null) step("dismiss=" + click(dismiss));
        }
        hideMask();
        Log.i(TAG, "RESULT run=" + run + " status=" + status + " author=" + author + " url=" + url
                + " total=" + (SystemClock.uptimeMillis() - t0) + "ms mask=" + useMask + " steps=" + steps);
        busy = false;
        h.postDelayed(this::updateButton, 300);
    }

    // ---------- cover (stands in for the group picker) ----------

    void showMask() {
        // Window-level key handling: Back cancels even though no child view holds focus.
        mask = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) abort("back-pressed");
                    return true;
                }
                return super.dispatchKeyEvent(ev);
            }
        };
        mask.setBackgroundColor(0xFF0F1014);
        TextView t = new TextView(this);
        t.setText("Saving…\n\nTap anywhere to cancel");
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFF4EFE6);
        t.setTextSize(20);
        mask.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // Not focusable-in-touch-mode: otherwise the first tap only takes focus and never counts as a click.
        mask.setOnClickListener(v -> abort("user-tapped-cancel"));
        mask.setOnTouchListener((v, ev) -> {
            Log.i(TAG, "maskTouch action=" + ev.getActionMasked() + " deviceId=" + ev.getDeviceId()
                    + " source=0x" + Integer.toHexString(ev.getSource()) + " at " + (int) ev.getRawX() + "," + (int) ev.getRawY()
                    + " t+" + (SystemClock.uptimeMillis() - t0) + "ms");
            return false;
        });
        mask.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public boolean performAccessibilityAction(android.view.View host, int action, android.os.Bundle args) {
                Log.i(TAG, "maskA11yAction " + action);
                return super.performAccessibilityAction(host, action, args);
            }
        });
        mask.getViewTreeObserver().addOnWindowFocusChangeListener(f -> step("maskFocus=" + f));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wm.addView(mask, lp);
    }

    void hideMask() {
        if (mask != null) {
            try { wm.removeViewImmediate(mask); } catch (Exception e) { Log.w(TAG, "hideMask " + e); }
            mask = null;
        }
    }

    // ---------- helpers ----------

    void waitFor(Finder f, long timeout, Consumer<AccessibilityNodeInfo> onFound, Runnable onTimeout) {
        long start = SystemClock.uptimeMillis();
        h.post(new Runnable() {
            @Override public void run() {
                if (!busy) return;
                try {
                    AccessibilityNodeInfo n = f.find();
                    if (n != null) { step("found"); onFound.accept(n); return; }
                    if (SystemClock.uptimeMillis() - start > timeout) { onTimeout.run(); return; }
                    h.postDelayed(this, 50);
                } catch (Exception e) {
                    abort("error " + e);
                }
            }
        });
    }

    AccessibilityNodeInfo pickVisible(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        int bestArea = 0;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            int area = Math.max(0, r.width()) * Math.max(0, r.height());
            if (area > bestArea) { bestArea = area; best = n; }
        }
        return best;
    }

    AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo n, String desc, boolean visibleOnly) {
        if (n == null) return null;
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().equalsIgnoreCase(desc) && (!visibleOnly || n.isVisibleToUser())) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByDesc(n.getChild(i), desc, visibleOnly);
            if (found != null) return found;
        }
        return null;
    }

    AccessibilityNodeInfo findByDescOrText(AccessibilityNodeInfo n, String s) {
        if (n == null) return null;
        CharSequence d = n.getContentDescription(), t = n.getText();
        if ((d != null && d.toString().equalsIgnoreCase(s)) || (t != null && t.toString().equalsIgnoreCase(s))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByDescOrText(n.getChild(i), s);
            if (found != null) return found;
        }
        return null;
    }

    boolean click(AccessibilityNodeInfo n) {
        // Try the node and up to 4 ancestors with ACTION_CLICK; log which level worked.
        AccessibilityNodeInfo c = n;
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < 5 && c != null; i++) {
            chain.append(i).append(c.getViewIdResourceName() == null ? "?" : c.getViewIdResourceName().replace(IG + ":id/", ""))
                    .append(c.isClickable() ? "[C]" : "").append(' ');
            if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                step("clickOK@lvl" + i + " chain=" + chain);
                return true;
            }
            c = c.getParent();
        }
        step("clickFAILED chain=" + chain + " -> gesture");
        return tapGesture(n);
    }

    boolean tapGesture(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        android.accessibilityservice.GestureDescription g = new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50)).build();
        if (mask != null) {
            // let the injected tap pass through our cover to Instagram
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mask.getLayoutParams();
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            wm.updateViewLayout(mask, lp);
        }
        return dispatchGesture(g, null, null);
    }
}
