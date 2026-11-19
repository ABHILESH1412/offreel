package dev.offreel.capture

import android.content.Context
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A full-screen Compose window drawn by the accessibility service over Instagram.
 *
 * It starts NOT focusable: taking focus makes Instagram close its share sheet before the link is
 * copied. [setFocusable] is turned on once the link is copied (needed to read the clipboard and
 * to type a new group name). One instance per save; [dismiss] is final.
 */
class OverlayWindow(
    private val context: Context,
    private val onBack: () -> Unit,
    private val content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    val isShowing get() = root != null

    fun show() {
        if (root != null) return
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val frame = object : FrameLayout(context) {
            // Back reaches us only while focusable; before that it goes to Instagram.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onBack()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        frame.setViewTreeLifecycleOwner(this)
        frame.setViewTreeSavedStateRegistryOwner(this)
        frame.setViewTreeViewModelStoreOwner(this)
        val addedAt = android.os.SystemClock.uptimeMillis()
        frame.addView(ComposeView(context).apply { setContent(content) })
        frame.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            var logged = false
            override fun onDraw() {
                if (logged) return
                logged = true
                android.util.Log.d("offreel.capture", "timing: overlay first drawn ${android.os.SystemClock.uptimeMillis() - addedAt} ms after adding")
            }
        })
        wm.addView(frame, params)
        root = frame
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun setFocusable(focusable: Boolean) {
        val r = root ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val newFlags = if (focusable) params.flags and flag.inv() else params.flags or flag
        if (newFlags == params.flags) return
        params.flags = newFlags
        wm.updateViewLayout(r, params)
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        try { wm.removeViewImmediate(r) } catch (_: Exception) { }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
