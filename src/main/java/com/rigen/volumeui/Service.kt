package com.rigen.volumeui

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.roundToInt

/**
 * Every change to the system volumes goes through here, honoring the safe limits.
 * Keeping the commands in one place lets other parts (widgets, the bot...) reuse them later.
 */
class VolumeController(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun level(stream: Int): Int = audio.getStreamVolume(stream)

    fun max(stream: Int): Int = audio.getStreamMaxVolume(stream).coerceAtLeast(1)

    fun activeStream(): Int =
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
            AudioManager.STREAM_VOICE_CALL
        } else {
            AudioManager.STREAM_MUSIC
        }

    fun isMusicActive(): Boolean = audio.isMusicActive

    /** The highest level (in steps) the safe limit allows for this stream. */
    fun limitStep(stream: Int, limits: List<Int>): Int {
        val index = STREAMS.indexOfFirst { it.stream == stream }
        val pct = if (index >= 0) limits.getOrElse(index) { 100 } else 100
        return (max(stream) * pct / 100f).toInt().coerceAtLeast(1)
    }

    /** Sets a level (in steps), never above the limit. Returns the level actually set. */
    fun setLevel(stream: Int, level: Int, limits: List<Int>): Int {
        audio.setStreamVolume(stream, level.coerceIn(0, limitStep(stream, limits)), 0)
        return audio.getStreamVolume(stream)
    }

    /** Sets a level as a percent of the maximum (0..100). */
    fun setPercent(stream: Int, percent: Int, limits: List<Int>): Int =
        setLevel(stream, (max(stream) * percent.coerceIn(0, 100) / 100f).roundToInt(), limits)

    /** One step up or down. Going up stops at the limit. Returns the new level. */
    fun step(stream: Int, raise: Boolean, limits: List<Int>): Int {
        if (raise) {
            if (level(stream) < limitStep(stream, limits)) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
            }
        } else {
            audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
        }
        return level(stream)
    }

    /** Sends a media key (previous / play-pause / next) to whatever is playing. */
    fun dispatchMedia(keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}

class VolumeAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    // A ComposeView outside an Activity needs these owners, otherwise it never draws.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hidePanel() }
    private val removeRunnable = Runnable { removePanel() }
    private val collapseRunnable = Runnable { updateExpanded(false) }
    private lateinit var windowManager: WindowManager
    private lateinit var controller: VolumeController

    private var panelRoot: View? = null
    private var panelCompose: ComposeView? = null
    private var visibleState: MutableTransitionState<Boolean>? = null

    // What the panel shows.
    private var level by mutableIntStateOf(0)
    private var maxLevel by mutableIntStateOf(1)
    private var currentStream by mutableIntStateOf(AudioManager.STREAM_MUSIC)
    private var settings by mutableStateOf(PanelSettings())
    private var dnd by mutableStateOf(false)
    private var expanded by mutableStateOf(false)
    private var mediaPlaying by mutableStateOf(false)
    private val stationLevels = mutableStateMapOf<Int, Int>()
    private val stationMax = mutableStateMapOf<Int, Int>()

    // Which app is in front, for the per-app rules.
    private var foregroundPackage: String? = null
    private val launchableCache = mutableMapOf<String, Boolean>()

    // Key handling: double-press mute and hold-to-repeat.
    private var lastPressKey = 0
    private var lastPressTime = 0L
    private var volumeBeforePress = 0
    private var wasRepeating = false
    private val preMuteVolume = mutableMapOf<Int, Int>()
    private var heldCode = 0
    private var heldDirection = 0 // +1 up, -1 down, 0 none
    private var heldSince = 0L
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (heldDirection == 0) return
            if (SystemClock.uptimeMillis() - heldSince > MAX_HOLD_MS) {
                stopRepeat()
                return
            }
            wasRepeating = true
            val stream = controller.activeStream()
            val changed = stepVolume(stream, heldDirection > 0, settings.volumeLimits)
            showPanel(stream)
            if (changed) haptic()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val actions = PanelActions(
        onSeek = { stream, fraction -> seekStream(stream, fraction) },
        onTouch = { down -> onPanelTouch(down) },
        onToggleStation = { updateExpanded(!expanded) },
        onMedia = { keyCode -> sendMedia(keyCode) },
    )

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        controller = VolumeController(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    // ───────────── Which app is in front ─────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore ourselves, the system UI and anything that is not a normal app (keyboards...).
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        if (!isLaunchable(pkg)) return
        foregroundPackage = pkg
    }

    private fun isLaunchable(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }

    override fun onInterrupt() = Unit

    // ───────────── Keys ─────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        if (event.action == KeyEvent.ACTION_UP) {
            if (code == heldCode) stopRepeat()
            // After holding a key, a quick new press must not count as a double press.
            if (wasRepeating) {
                lastPressTime = 0L
                wasRepeating = false
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0) return true // holding is handled by our own timer

        val s = Prefs.load(this)
        val stream = controller.activeStream()
        val raise = code == KeyEvent.KEYCODE_VOLUME_UP
        val isMuteKey = (s.doublePressKey == 1 && raise) || (s.doublePressKey == 2 && !raise)
        val now = SystemClock.uptimeMillis()

        if (isMuteKey && lastPressKey == code && now - lastPressTime <= DOUBLE_PRESS_MS) {
            lastPressTime = 0L
            stopRepeat()
            toggleMute(stream, s.volumeLimits)
            showPanel(stream)
            return true
        }

        lastPressKey = code
        lastPressTime = now
        volumeBeforePress = controller.level(stream)
        val changed = stepVolume(stream, raise, s.volumeLimits)
        showPanel(stream)
        if (changed) haptic()
        startRepeat(code, if (raise) 1 else -1)
        return true // consume the key so the system panel stays hidden
    }

    /** One step. Returns true if the level changed. */
    private fun stepVolume(stream: Int, raise: Boolean, limits: List<Int>): Boolean {
        return try {
            val before = controller.level(stream)
            controller.step(stream, raise, limits) != before
        } catch (e: Exception) {
            Log.e(TAG, "Could not change volume", e)
            false
        }
    }

    private fun startRepeat(code: Int, direction: Int) {
        stopRepeat()
        heldCode = code
        heldDirection = direction
        heldSince = SystemClock.uptimeMillis()
        handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatRunnable)
        heldCode = 0
        heldDirection = 0
    }

    /** Second press of a double press: undo the first press's step, then mute or unmute. */
    private fun toggleMute(stream: Int, limits: List<Int>) {
        try {
            controller.setLevel(stream, volumeBeforePress, limits)
            val current = controller.level(stream)
            if (current > 0) {
                preMuteVolume[stream] = current
                controller.setLevel(stream, 0, limits)
            } else {
                val restore = preMuteVolume[stream] ?: (controller.max(stream) / 2)
                controller.setLevel(stream, restore.coerceAtLeast(1), limits)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not toggle mute", e)
        }
    }

    private fun haptic() {
        if (!settings.haptics) return
        panelCompose?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun isDndOn(): Boolean {
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        val f = nm.currentInterruptionFilter
        return f == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
            f == NotificationManager.INTERRUPTION_FILTER_NONE ||
            f == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    // ───────────── Showing the panel ─────────────

    fun showPanel(stream: Int = controller.activeStream(), ignoreRules: Boolean = false) {
        // Re-read the saved settings every time, so changes made in the app apply immediately.
        val loaded = Prefs.load(this)
        val pkg = foregroundPackage
        val mode = if (ignoreRules || pkg == null) RULE_NORMAL else (loaded.appRules[pkg] ?: RULE_NORMAL)
        settings = if (mode == RULE_ALT_POSITION) {
            loaded.copy(posX = loaded.altPosX, posY = loaded.altPosY)
        } else {
            loaded
        }
        if (mode == RULE_HIDE) return

        currentStream = stream
        level = controller.level(stream)
        maxLevel = controller.max(stream)
        dnd = isDndOn()
        mediaPlaying = controller.isMusicActive()
        if (expanded) refreshStation()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        if (panelRoot == null) addPanel() else updateLayout()
        visibleState?.targetState = true
        if (expanded) resetIdle() else handler.postDelayed(hideRunnable, settings.hideDelayMs.toLong())
    }

    private fun refreshStation() {
        STREAMS.forEach { info ->
            stationMax[info.stream] = controller.max(info.stream)
            stationLevels[info.stream] = controller.level(info.stream)
        }
    }

    /** A bar was touched (the main one or one in the Volume Station). */
    private fun seekStream(stream: Int, fraction: Float) {
        try {
            val max = controller.max(stream)
            val before = controller.level(stream)
            val after = controller.setLevel(stream, (fraction * max).roundToInt(), settings.volumeLimits)
            if (after != before) haptic()
            if (stream == currentStream) level = after
            stationLevels[stream] = after
        } catch (e: SecurityException) {
            // Ring and notification volumes can need "Do Not Disturb access".
            Toast.makeText(this, getString(R.string.toast_dnd_needed), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Could not set volume", e)
        }
        if (expanded) resetIdle()
    }

    private fun sendMedia(keyCode: Int) {
        controller.dispatchMedia(keyCode)
        haptic()
        if (expanded) resetIdle()
        handler.postDelayed({ mediaPlaying = controller.isMusicActive() }, 400L)
    }

    /** Keeps the panel on screen while a finger is on it. */
    private fun onPanelTouch(down: Boolean) {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        if (expanded) {
            handler.removeCallbacks(collapseRunnable)
            if (!down) resetIdle()
        } else if (!down) {
            handler.postDelayed(hideRunnable, settings.hideDelayMs.toLong())
        }
    }

    /** Opens or closes the Volume Station part of the panel. */
    private fun updateExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        if (value) {
            refreshStation()
            mediaPlaying = controller.isMusicActive()
            resetIdle()
        } else {
            handler.postDelayed(hideRunnable, settings.hideDelayMs.toLong())
        }
        updateLayout()
    }

    private fun resetIdle() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, STATION_IDLE_MS)
    }

    private fun onOutsideTouch() {
        if (expanded) updateExpanded(false)
    }

    // ───────────── The window ─────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun screenSizePx(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** Size of the window in pixels, closed or open (it includes 12dp of room around the panel). */
    private fun panelSizePx(open: Boolean): Pair<Int, Int> {
        val s = settings
        val pad = 24
        if (!s.vertical) {
            val rowH = s.heightDp.coerceAtMost(EXTRA_ROW_MAX_H_DP)
            val others = STREAMS.size - 1
            val extra = if (open) others * (rowH + PANEL_GAP_DP) + PANEL_MEDIA_H_DP + PANEL_GAP_DP else 0
            return dp(s.widthDp + pad) to dp(s.heightDp + extra + pad)
        }
        val colW = s.widthDp.coerceAtMost(EXTRA_COL_MAX_W_DP)
        val extra = if (open) (STREAMS.size - 1) * colW + colW else 0
        return dp(s.widthDp + extra + pad) to dp(s.heightDp + pad)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val (screenW, screenH) = screenSizePx()
        val (w0, h0) = panelSizePx(false)
        val (w1, h1) = panelSizePx(expanded)
        // posX/posY are the center of the closed panel; keep it inside the screen.
        val x0 = (settings.posX * screenW - w0 / 2f).toInt().coerceIn(0, (screenW - w0).coerceAtLeast(0))
        val y0 = (settings.posY * screenH - h0 / 2f).toInt().coerceIn(0, (screenH - h0).coerceAtLeast(0))
        var px = x0
        var py = y0
        if (expanded) {
            // Open toward the side with more room, keeping the main row where it was.
            if (settings.vertical) {
                px = if (settings.posX > 0.5f) x0 + w0 - w1 else x0
                px = px.coerceIn(0, (screenW - w1).coerceAtLeast(0))
            } else {
                py = if (settings.posY > 0.5f) y0 + h0 - h1 else y0
                py = py.coerceIn(0, (screenH - h1).coerceAtLeast(0))
            }
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!settings.touchEnabled && !settings.stationEnabled) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Needs no "draw over other apps" permission, unlike TYPE_APPLICATION_OVERLAY.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = px
            this.y = py
        }
    }

    private fun updateLayout() {
        val view = panelRoot ?: return
        try {
            windowManager.updateViewLayout(view, buildLayoutParams())
        } catch (e: Exception) {
            Log.e(TAG, "Could not move volume panel", e)
        }
    }

    /** The window's root view. It notices touches outside the panel, to close the Station. */
    private inner class PanelRoot(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
            return super.dispatchTouchEvent(ev)
        }
    }

    @Composable
    private fun PanelHost(vs: MutableTransitionState<Boolean>) {
        // Slide in from the nearest screen edge.
        val sign = if (settings.vertical) {
            if (settings.posX > 0.5f) 1 else -1
        } else {
            if (settings.posY > 0.5f) 1 else -1
        }
        val slideIn = if (settings.vertical) {
            slideInHorizontally(tween(200)) { sign * it / 3 }
        } else {
            slideInVertically(tween(200)) { sign * it / 3 }
        }
        val slideOut = if (settings.vertical) {
            slideOutHorizontally(tween(160)) { sign * it / 3 }
        } else {
            slideOutVertically(tween(160)) { sign * it / 3 }
        }
        AnimatedVisibility(
            visibleState = vs,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f) + slideIn,
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f) + slideOut,
        ) {
            VolumePanel(
                settings = settings,
                state = PanelState(
                    stream = currentStream,
                    level = level,
                    max = maxLevel,
                    dnd = dnd,
                    expanded = expanded,
                    levels = stationLevels,
                    maxes = stationMax,
                    mediaPlaying = mediaPlaying,
                ),
                modifier = Modifier.padding(12.dp),
                actions = actions,
            )
        }
    }

    private fun addPanel() {
        try {
            val vs = MutableTransitionState(false)
            val compose = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@VolumeAccessibilityService)
                setViewTreeSavedStateRegistryOwner(this@VolumeAccessibilityService)
                setContent { PanelHost(vs) }
            }
            val root = PanelRoot(this)
            root.addView(
                compose,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            windowManager.addView(root, buildLayoutParams())
            panelRoot = root
            panelCompose = compose
            visibleState = vs
        } catch (e: Exception) {
            Log.e(TAG, "Could not show volume panel", e)
        }
    }

    /** Starts the exit animation, then removes the window once it has finished. */
    private fun hidePanel() {
        val vs = visibleState
        if (vs == null) {
            removePanel()
            return
        }
        vs.targetState = false
        handler.removeCallbacks(removeRunnable)
        handler.postDelayed(removeRunnable, EXIT_MS)
    }

    private fun removePanel() {
        val root = panelRoot ?: return
        val compose = panelCompose
        panelRoot = null
        panelCompose = null
        visibleState = null
        expanded = false
        handler.removeCallbacks(collapseRunnable)
        try {
            windowManager.removeView(root)
            compose?.disposeComposition()
        } catch (e: Exception) {
            Log.e(TAG, "Could not hide volume panel", e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        handler.removeCallbacks(collapseRunnable)
        stopRepeat()
        removePanel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KanadeSystem"
        private const val EXIT_MS = 260L
        private const val DOUBLE_PRESS_MS = 350L
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 110L
        private const val MAX_HOLD_MS = 15_000L
        private const val STATION_IDLE_MS = 6_000L

        @Volatile
        var instance: VolumeAccessibilityService? = null
    }
}
