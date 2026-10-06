package com.rigen.volumeui

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import androidx.compose.runtime.remember
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

    fun ringerMode(): Int = audio.ringerMode

    fun setRingerMode(mode: Int) {
        audio.ringerMode = mode
    }

    /** Where the sound is going: the built-in speaker, Bluetooth, or wired headphones. */
    fun outputKind(): Int {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetooth = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (bluetooth) return OUTPUT_BLUETOOTH
        val wired = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return if (wired) OUTPUT_WIRED else OUTPUT_SPEAKER
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
    private val collapseRunnable = Runnable { changeExpanded(false) }
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
    private var ringerMode by mutableIntStateOf(AudioManager.RINGER_MODE_NORMAL)
    private var outputKind by mutableIntStateOf(OUTPUT_SPEAKER)
    private var mediaApp by mutableStateOf<String?>(null)
    private var mediaTitle by mutableStateOf<String?>(null)
    private var mediaController: MediaController? = null
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
    private val preMuteAll = mutableMapOf<Int, Int>()
    private var heldCode = 0
    private var heldDirection = 0 // +1 up, -1 down, 0 none
    private var heldSince = 0L
    private val repeatRunnable = object : Runnable {
        override fun run() {
            try {
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
            } catch (e: Throwable) {
                logError("repeat", e)
            }
        }
    }

    private fun buildActions(relative: Boolean) = PanelActions(
        onSeek = { stream, fraction -> seekStream(stream, fraction) },
        onTouch = { down -> onPanelTouch(down) },
        onToggleStation = { changeExpanded(!expanded) },
        onMedia = { keyCode -> sendMedia(keyCode) },
        onMuteAll = { muteAll() },
        onToggleDnd = { toggleDnd() },
        onOpenSettings = { openSettings() },
        onToggleMute = { toggleMuteCurrent() },
        // The service already plays a tick whenever a drag changes the level (see seekStream).
        relativeDrag = relative,
        onToggleMuteStream = { stream -> toggleMuteStream(stream) },
        onToggleRinger = { toggleRinger() },
        onOpenOutput = { openOutputSwitcher() },
    )

    override fun onCreate() {
        super.onCreate()
        Diag.mark(this, "svc_created")
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
        Diag.mark(this, "svc_connected")
    }

    /** Keeps a note of an error (shown in the app) without ever crashing the service. */
    private fun logError(where: String, e: Throwable) {
        Log.e(TAG, where, e)
        try {
            Diag.error(applicationContext, where, e)
        } catch (ignored: Throwable) {
            // Nothing more we can do here.
        }
    }

    // ───────────── Which app is in front ─────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            // Ignore ourselves, the system UI and anything that is not a normal app (keyboards...).
            if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
            if (!isLaunchable(pkg)) return
            foregroundPackage = pkg
        } catch (e: Throwable) {
            logError("onAccessibilityEvent", e)
        }
    }

    private fun isLaunchable(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }

    override fun onInterrupt() = Unit

    // ───────────── Keys ─────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return try {
            handleKey(event)
        } catch (e: Throwable) {
            logError("onKeyEvent", e)
            false
        }
    }

    private fun handleKey(event: KeyEvent): Boolean {
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
            logError("Could not change volume", e)
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
            logError("Could not toggle mute", e)
        }
    }

    private fun haptic() {
        if (!settings.haptics) return
        try {
            Haptics.tick(applicationContext)
        } catch (e: Throwable) {
            logError("haptic", e)
        }
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
        try {
            showPanelNow(stream, ignoreRules)
        } catch (e: Throwable) {
            logError("showPanel", e)
        }
    }

    private fun showPanelNow(stream: Int, ignoreRules: Boolean) {
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
        try {
            ringerMode = controller.ringerMode()
            outputKind = controller.outputKind()
        } catch (e: Throwable) {
            logError("refreshStation", e)
        }
        refreshNowPlaying()
    }

    /** The app that is playing media and its title. Needs media access; off by default. */
    private fun refreshNowPlaying() {
        mediaController = null
        mediaApp = null
        mediaTitle = null
        if (!settings.stationNowPlaying) return
        try {
            val manager = getSystemService(MediaSessionManager::class.java) ?: return
            val sessions = manager.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
            val session = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
                ?: return
            mediaController = session
            mediaApp = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(session.packageName, 0)).toString()
            } catch (e: Exception) {
                session.packageName
            }
            mediaTitle = session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        } catch (e: SecurityException) {
            // Media access was not granted (or was taken back): just show nothing.
        } catch (e: Exception) {
            logError("refreshNowPlaying", e)
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
            toastDndNeeded()
        } catch (e: Exception) {
            logError("Could not set volume", e)
        }
        if (expanded) resetIdle()
    }

    private fun sendMedia(keyCode: Int) {
        // With media access the buttons talk to the app shown in the card; otherwise to whatever plays.
        val session = mediaController
        if (session != null && settings.stationNowPlaying) {
            try {
                val controls = session.transportControls
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                    else -> if (session.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
            } catch (e: Throwable) {
                logError("sendMedia", e)
                controller.dispatchMedia(keyCode)
            }
        } else {
            controller.dispatchMedia(keyCode)
        }
        haptic()
        if (expanded) resetIdle()
        handler.postDelayed({
            mediaPlaying = controller.isMusicActive()
            if (expanded) refreshNowPlaying()
        }, 400L)
    }

    /** The speaker icon of one bar in the Station: mute that volume, or bring it back. */
    private fun toggleMuteStream(stream: Int) {
        try {
            val limits = settings.volumeLimits
            val now = controller.level(stream)
            if (now > 0) {
                preMuteVolume[stream] = now
                controller.setLevel(stream, 0, limits)
            } else {
                val back = preMuteVolume[stream] ?: (controller.max(stream) / 2)
                controller.setLevel(stream, back.coerceAtLeast(1), limits)
            }
            val after = controller.level(stream)
            stationLevels[stream] = after
            if (stream == currentStream) level = after
            ringerMode = controller.ringerMode() // muting the ring volume can change the ringer mode
            haptic()
        } catch (e: SecurityException) {
            toastDndNeeded()
        } catch (e: Throwable) {
            logError("toggleMuteStream", e)
        }
        if (expanded) resetIdle()
    }

    /** Sound, then vibrate, then silent, then sound again. */
    private fun toggleRinger() {
        try {
            val next = when (controller.ringerMode()) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            try {
                controller.setRingerMode(next)
            } catch (e: SecurityException) {
                // Silent needs "Do Not Disturb access": go back to sound instead of getting stuck.
                toastDndNeeded()
                controller.setRingerMode(AudioManager.RINGER_MODE_NORMAL)
            }
            ringerMode = controller.ringerMode()
            stationLevels[AudioManager.STREAM_RING] = controller.level(AudioManager.STREAM_RING)
            haptic()
        } catch (e: Throwable) {
            logError("toggleRinger", e)
        }
        if (expanded) resetIdle()
    }

    /**
     * Android does not let an app move another app's sound to a different output, so this opens
     * the system's own volume panel (it has the output switcher) and closes the Station.
     */
    private fun openOutputSwitcher() {
        try {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val intent = if (Build.VERSION.SDK_INT >= 29) {
                Intent(Settings.Panel.ACTION_VOLUME)
            } else {
                Intent(Settings.ACTION_SOUND_SETTINGS)
            }.addFlags(flags)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(flags))
            }
            changeExpanded(false)
        } catch (e: Throwable) {
            logError("openOutputSwitcher", e)
        }
    }

    /** The capsule's speaker button: mute the main volume, or bring it back. */
    private fun toggleMuteCurrent() {
        try {
            val stream = currentStream
            val limits = settings.volumeLimits
            val now = controller.level(stream)
            if (now > 0) {
                preMuteVolume[stream] = now
                controller.setLevel(stream, 0, limits)
            } else {
                val back = preMuteVolume[stream] ?: (controller.max(stream) / 2)
                controller.setLevel(stream, back.coerceAtLeast(1), limits)
            }
            level = controller.level(stream)
            haptic()
            onPanelTouch(false) // keep the panel on screen a little longer
        } catch (e: Throwable) {
            logError("toggleMuteCurrent", e)
        }
    }

    /** Mutes every volume, or brings them back to what they were. */
    private fun muteAll() {
        try {
            val limits = settings.volumeLimits
            val anyOn = STREAMS.any { controller.level(it.stream) > 0 }
            STREAMS.forEach { info ->
                try {
                    if (anyOn) {
                        val now = controller.level(info.stream)
                        if (now > 0) {
                            preMuteAll[info.stream] = now
                            controller.setLevel(info.stream, 0, limits)
                        }
                    } else {
                        val before = preMuteAll[info.stream]
                        if (before != null) controller.setLevel(info.stream, before, limits)
                    }
                } catch (e: SecurityException) {
                    // Ring and notification volumes can need "Do Not Disturb access".
                    toastDndNeeded()
                }
            }
            if (!anyOn) preMuteAll.clear()
            refreshStation()
            level = controller.level(currentStream)
            haptic()
            resetIdle()
        } catch (e: Throwable) {
            logError("muteAll", e)
        }
    }

    /** Turns Do Not Disturb on or off. Needs the "Do Not Disturb access" permission. */
    private fun toggleDnd() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (!nm.isNotificationPolicyAccessGranted) {
                toastDndNeeded()
                return
            }
            val target = if (isDndOn()) {
                NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            }
            nm.setInterruptionFilter(target)
            handler.postDelayed({ dnd = isDndOn() }, 300L)
            haptic()
            resetIdle()
        } catch (e: Throwable) {
            logError("toggleDnd", e)
        }
    }

    /** The gear in the Station card: opens the Volume Panel settings in the app. */
    private fun openSettings() {
        try {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_PAGE, PAGE_VOLUME)
            startActivity(intent)
            changeExpanded(false)
        } catch (e: Throwable) {
            logError("openSettings", e)
        }
    }

    private fun toastDndNeeded() {
        Toast.makeText(this, Prefs.localized(this).getString(R.string.toast_dnd_needed), Toast.LENGTH_SHORT).show()
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
    private fun changeExpanded(value: Boolean) {
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
        if (expanded) changeExpanded(false)
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

    /** Size of the panel window in pixels (it includes 12dp of room around the panel). */
    private fun panelSizePx(): Pair<Int, Int> =
        dp(settings.widthDp + 24) to dp(settings.heightDp + 24)

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!settings.touchEnabled && !settings.stationEnabled) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Needs no "draw over other apps" permission, unlike TYPE_APPLICATION_OVERLAY.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        if (expanded) {
            // The Volume Station card sits in the middle of the screen.
            lp.gravity = Gravity.CENTER
            lp.x = 0
            lp.y = 0
            return lp
        }

        val (screenW, screenH) = screenSizePx()
        val (w0, h0) = panelSizePx()
        // posX/posY are the center of the panel; keep it inside the screen.
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (settings.posX * screenW - w0 / 2f).toInt().coerceIn(0, (screenW - w0).coerceAtLeast(0))
        lp.y = (settings.posY * screenH - h0 / 2f).toInt().coerceIn(0, (screenH - h0).coerceAtLeast(0))
        return lp
    }

    private fun updateLayout() {
        val view = panelRoot ?: return
        try {
            windowManager.updateViewLayout(view, buildLayoutParams())
        } catch (e: Exception) {
            logError("Could not move volume panel", e)
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
        val acts = remember(settings.relativeDrag) { buildActions(settings.relativeDrag) }
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
            PanelOrStation(
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
                    ringerMode = ringerMode,
                    outputKind = outputKind,
                    mediaApp = mediaApp,
                    mediaTitle = mediaTitle,
                ),
                modifier = Modifier.padding(12.dp),
                actions = acts,
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
            // Compose looks for these owners on the window's root view, which is this container.
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
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
            logError("Could not show volume panel", e)
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
            logError("Could not hide volume panel", e)
        }
    }

    override fun onDestroy() {
        Diag.mark(this, "svc_destroyed")
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
