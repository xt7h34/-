package com.rigen.volumeui

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────────── Settings ─────────────────────────────

data class PanelSettings(
    val cornerRadiusDp: Int = 24,
    val colorArgb: Int = 0xFF1E1E1E.toInt(),
    /** Bar color. 0 means "automatic" (picked for contrast with the panel color). */
    val barColorArgb: Int = 0,
    /** Real on-screen size of the panel: width = horizontal extent, height = vertical extent. */
    val widthDp: Int = 260,
    val heightDp: Int = 56,
    val vertical: Boolean = false,
    val showFrame: Boolean = true,
    val hideDelayMs: Int = 1500,
    val redThresholdPct: Int = 85,
    val showDndIcon: Boolean = true,
    /** Center of the panel as a fraction of the screen (0..1). */
    val posX: Float = 0.5f,
    val posY: Float = 0.08f,
    /** Lets the user drag on the bar to change the volume. */
    val touchEnabled: Boolean = true,
    /** 0 = off, 1 = double-press volume up to mute, 2 = double-press volume down to mute. */
    val doublePressKey: Int = 0,
    /** Volume Station: the three-dots handle that opens every volume slider. */
    val stationEnabled: Boolean = false,
    val stationX: Float = 0.94f,
    val stationY: Float = 0.45f,
)

/** Single source of truth for saved settings. Used by both the app screen and the service. */
object Prefs {
    private const val FILE = "kanade_system"
    private const val K_RADIUS = "corner_radius_dp"
    private const val K_COLOR = "color_argb"
    private const val K_BAR_COLOR = "bar_color_argb"
    private const val K_WIDTH = "width_dp"
    private const val K_HEIGHT = "height_dp"
    private const val K_VERTICAL = "vertical"
    private const val K_FRAME = "show_frame"
    private const val K_DELAY = "hide_delay_ms"
    private const val K_RED = "red_threshold_pct"
    private const val K_DND = "show_dnd_icon"
    private const val K_POS_X = "pos_x"
    private const val K_POS_Y = "pos_y"
    private const val K_TOUCH = "touch_enabled"
    private const val K_DOUBLE = "double_press_key"
    private const val K_STATION = "station_enabled"
    private const val K_STATION_X = "station_x"
    private const val K_STATION_Y = "station_y"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun registerListener(context: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp(context).registerOnSharedPreferenceChangeListener(l)
    }

    fun unregisterListener(context: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp(context).unregisterOnSharedPreferenceChangeListener(l)
    }

    fun load(context: Context): PanelSettings {
        val d = PanelSettings()
        val p = sp(context)
        return PanelSettings(
            cornerRadiusDp = p.getInt(K_RADIUS, d.cornerRadiusDp),
            colorArgb = p.getInt(K_COLOR, d.colorArgb),
            barColorArgb = p.getInt(K_BAR_COLOR, d.barColorArgb),
            widthDp = p.getInt(K_WIDTH, d.widthDp),
            heightDp = p.getInt(K_HEIGHT, d.heightDp),
            vertical = p.getBoolean(K_VERTICAL, d.vertical),
            showFrame = p.getBoolean(K_FRAME, d.showFrame),
            hideDelayMs = p.getInt(K_DELAY, d.hideDelayMs),
            redThresholdPct = p.getInt(K_RED, d.redThresholdPct),
            showDndIcon = p.getBoolean(K_DND, d.showDndIcon),
            posX = p.getFloat(K_POS_X, d.posX),
            posY = p.getFloat(K_POS_Y, d.posY),
            touchEnabled = p.getBoolean(K_TOUCH, d.touchEnabled),
            doublePressKey = p.getInt(K_DOUBLE, d.doublePressKey),
            stationEnabled = p.getBoolean(K_STATION, d.stationEnabled),
            stationX = p.getFloat(K_STATION_X, d.stationX),
            stationY = p.getFloat(K_STATION_Y, d.stationY),
        )
    }

    fun save(context: Context, s: PanelSettings) {
        sp(context).edit()
            .putInt(K_RADIUS, s.cornerRadiusDp)
            .putInt(K_COLOR, s.colorArgb)
            .putInt(K_BAR_COLOR, s.barColorArgb)
            .putInt(K_WIDTH, s.widthDp)
            .putInt(K_HEIGHT, s.heightDp)
            .putBoolean(K_VERTICAL, s.vertical)
            .putBoolean(K_FRAME, s.showFrame)
            .putInt(K_DELAY, s.hideDelayMs)
            .putInt(K_RED, s.redThresholdPct)
            .putBoolean(K_DND, s.showDndIcon)
            .putFloat(K_POS_X, s.posX)
            .putFloat(K_POS_Y, s.posY)
            .putBoolean(K_TOUCH, s.touchEnabled)
            .putInt(K_DOUBLE, s.doublePressKey)
            .putBoolean(K_STATION, s.stationEnabled)
            .putFloat(K_STATION_X, s.stationX)
            .putFloat(K_STATION_Y, s.stationY)
            .apply()
    }
}

// ───────────────────────────── Icons & panel ─────────────────────────────

/** Small built-in icons, drawn from 24x24 vector path data (no extra library needed). */
enum class StreamKind(val pathData: String) {
    MEDIA("M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"),
    CALL("M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"),
    RING("M23.71,16.67C20.66,13.78 16.54,12 12,12 7.46,12 3.34,13.78 0.29,16.67c-0.18,0.18 -0.29,0.43 -0.29,0.71 0,0.28 0.11,0.53 0.29,0.71l2.48,2.48c0.18,0.18 0.43,0.29 0.71,0.29 0.27,0 0.52,-0.11 0.7,-0.28 0.79,-0.74 1.69,-1.36 2.66,-1.85 0.33,-0.16 0.56,-0.5 0.56,-0.9v-3.1c1.45,-0.48 3,-0.73 4.6,-0.73s3.15,0.25 4.6,0.73v3.1c0,0.39 0.23,0.74 0.56,0.9 0.98,0.49 1.87,1.12 2.66,1.87 0.18,0.17 0.43,0.28 0.7,0.28 0.28,0 0.53,-0.11 0.71,-0.29l2.48,-2.48c0.18,-0.18 0.29,-0.43 0.29,-0.71 0,-0.27 -0.11,-0.52 -0.29,-0.7zM21.16,6.26l-1.41,-1.41 -3.56,3.55 1.41,1.41s3.45,-3.52 3.56,-3.55zM13,2h-2v5h2V2zM6.4,9.81L7.81,8.4 4.26,4.84 2.84,6.26c0.11,0.03 3.56,3.55 3.56,3.55z"),
    NOTIFICATION("M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"),
    ALARM("M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8H11v6l4.75,2.85 0.75,-1.23 -4,-2.37V8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z"),
    MUTED("M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z"),
    DND("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13H7v-2h10v2z"),
}

private val HIGH_VOLUME_RED = Color(0xFFE53935)

/** Every volume the Volume Station lists. */
private val STATION_STREAMS = listOf(
    AudioManager.STREAM_MUSIC to StreamKind.MEDIA,
    AudioManager.STREAM_VOICE_CALL to StreamKind.CALL,
    AudioManager.STREAM_RING to StreamKind.RING,
    AudioManager.STREAM_NOTIFICATION to StreamKind.NOTIFICATION,
    AudioManager.STREAM_ALARM to StreamKind.ALARM,
)
private const val STATION_W_DP = 230
private const val STATION_ROW_H_DP = 44
private const val STATION_GAP_DP = 6
private const val STATION_PAD_DP = 8
private const val HANDLE_W_DP = 36
private const val HANDLE_H_DP = 72

@Composable
fun StreamIcon(kind: StreamKind, color: Color, iconSize: Dp, modifier: Modifier = Modifier) {
    val path = remember(kind) {
        runCatching { PathParser().parsePathString(kind.pathData).toPath() }.getOrNull()
    }
    Canvas(modifier.size(iconSize)) {
        val p = path ?: return@Canvas
        scale(scale = this.size.minDimension / 24f, pivot = Offset.Zero) {
            drawPath(p, color)
        }
    }
}

/**
 * The volume panel itself (horizontal or vertical). Used for the in-app preview, the real
 * overlay and the Volume Station rows. When [onSeek] is given, dragging on the bar reports
 * the touched position (0..1).
 */
@Composable
fun VolumePanel(
    settings: PanelSettings,
    level: Int,
    max: Int,
    kind: StreamKind,
    dnd: Boolean,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
    onTouch: ((Boolean) -> Unit)? = null,
) {
    val vertical = settings.vertical
    val fraction = if (max > 0) (level.toFloat() / max).coerceIn(0f, 1f) else 0f
    val pct = (fraction * 100).roundToInt()
    val frameBg = Color(settings.colorArgb)
    val autoFg = when {
        !settings.showFrame -> Color.White
        frameBg.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    val barColor = if (settings.barColorArgb == 0) autoFg else Color(settings.barColorArgb)
    val iconColor = if (pct >= settings.redThresholdPct) HIGH_VOLUME_RED else autoFg
    val iconKind = if (level == 0) StreamKind.MUTED else kind
    val animatedFraction by animateFloatAsState(fraction, tween(120), label = "bar")
    val crossDp = if (vertical) settings.widthDp else settings.heightDp
    val thickness = (crossDp / 6).coerceIn(4, 16)
    val iconSize = (crossDp - 24).coerceIn(16, 32)

    // Start and length of the bar along the panel's main axis, in pixels (relative to the panel).
    val barBounds = remember { FloatArray(2) }
    val seekCallback by rememberUpdatedState(onSeek)
    val touchCallback by rememberUpdatedState(onTouch)

    var shell = modifier.width(settings.widthDp.dp).height(settings.heightDp.dp)
    if (settings.showFrame) {
        shell = shell
            .clip(RoundedCornerShape(settings.cornerRadiusDp.dp))
            .background(frameBg)
    }

    val gestures = Modifier.pointerInput(vertical) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val seek = seekCallback ?: return@awaitEachGesture
            val slop = 24.dp.toPx()
            val start = barBounds[0]
            val length = barBounds[1]
            val downPos = if (vertical) down.position.y else down.position.x
            if (length <= 0f || downPos < start - slop || downPos > start + length + slop) {
                return@awaitEachGesture
            }
            fun fractionAt(p: Float): Float {
                val f = ((p - start) / length).coerceIn(0f, 1f)
                return if (vertical) 1f - f else f
            }
            touchCallback?.invoke(true)
            seek(fractionAt(downPos))
            down.consume()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { c ->
                    if (c.positionChanged()) {
                        seek(fractionAt(if (vertical) c.position.y else c.position.x))
                        c.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
            touchCallback?.invoke(false)
        }
    }

    if (vertical) {
        Column(
            modifier = shell
                .padding(horizontal = 4.dp, vertical = 14.dp)
                .then(gestures),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$pct",
                color = autoFg,
                fontSize = if (crossDp < 48) 11.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .width(thickness.dp)
                    .clip(RoundedCornerShape((thickness / 2).dp))
                    .background(barColor.copy(alpha = 0.25f))
                    .onGloballyPositioned {
                        barBounds[0] = it.positionInParent().y
                        barBounds[1] = it.size.height.toFloat()
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(animatedFraction)
                        .background(barColor),
                )
            }
            Spacer(Modifier.height(8.dp))
            StreamIcon(iconKind, iconColor, iconSize.dp)
            if (dnd && settings.showDndIcon) {
                Spacer(Modifier.height(6.dp))
                StreamIcon(StreamKind.DND, autoFg.copy(alpha = 0.9f), 14.dp)
            }
        }
    } else {
        Row(
            modifier = shell
                .padding(horizontal = 14.dp)
                .then(gestures),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StreamIcon(iconKind, iconColor, iconSize.dp)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(thickness.dp)
                    .clip(RoundedCornerShape((thickness / 2).dp))
                    .background(barColor.copy(alpha = 0.25f))
                    .onGloballyPositioned {
                        barBounds[0] = it.positionInParent().x
                        barBounds[1] = it.size.width.toFloat()
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFraction)
                        .background(barColor),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$pct",
                color = autoFg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(32.dp),
            )
            if (dnd && settings.showDndIcon) {
                Spacer(Modifier.width(8.dp))
                StreamIcon(StreamKind.DND, autoFg.copy(alpha = 0.9f), 16.dp)
            }
        }
    }
}

/** The small window the Volume Station opens: one slider row per volume. */
@Composable
fun StationPanel(
    settings: PanelSettings,
    levels: Map<Int, Int>,
    maxes: Map<Int, Int>,
    onSeek: (Int, Float) -> Unit,
    onTouch: (Boolean) -> Unit,
) {
    val rowSettings = settings.copy(
        widthDp = STATION_W_DP,
        heightDp = STATION_ROW_H_DP,
        vertical = false,
        showFrame = true,
    )
    Column(
        modifier = Modifier.padding(STATION_PAD_DP.dp),
        verticalArrangement = Arrangement.spacedBy(STATION_GAP_DP.dp),
    ) {
        STATION_STREAMS.forEach { (stream, kind) ->
            VolumePanel(
                settings = rowSettings,
                level = levels[stream] ?: 0,
                max = maxes[stream] ?: 1,
                kind = kind,
                dnd = false,
                onSeek = { f -> onSeek(stream, f) },
                onTouch = onTouch,
            )
        }
    }
}

// ───────────────────────────── Accessibility service ─────────────────────────────

class VolumeAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    // A ComposeView outside an Activity needs these owners, otherwise it never draws.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hidePanel() }
    private val removeRunnable = Runnable { removePanel() }
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager

    // Volume panel (shown on key presses).
    private var panelView: ComposeView? = null
    private var visibleState: MutableTransitionState<Boolean>? = null
    private var level by mutableIntStateOf(0)
    private var maxLevel by mutableIntStateOf(1)
    private var settings by mutableStateOf(PanelSettings())
    private var kind by mutableStateOf(StreamKind.MEDIA)
    private var dnd by mutableStateOf(false)
    private var currentStream = AudioManager.STREAM_MUSIC

    // Key handling: double-press mute and hold-to-repeat.
    private var lastPressKey = 0
    private var lastPressTime = 0L
    private var volumeBeforePress = 0
    private var wasRepeating = false
    private val preMuteVolume = mutableMapOf<Int, Int>()
    private var heldCode = 0
    private var heldDirection = 0
    private var heldSince = 0L
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (heldDirection == 0) return
            if (SystemClock.uptimeMillis() - heldSince > MAX_HOLD_MS) {
                stopRepeat()
                return
            }
            wasRepeating = true
            val stream = activeStream()
            audioManager.adjustStreamVolume(stream, heldDirection, 0)
            showPanel(stream)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // Volume Station.
    private var handleView: View? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var scrimView: View? = null
    private var stationView: ComposeView? = null
    private var stationState: MutableTransitionState<Boolean>? = null
    private val stationLevels = mutableStateMapOf<Int, Int>()
    private val stationMax = mutableMapOf<Int, Int>()
    private val closeStationRunnable = Runnable { closeStation() }
    private val removeStationRunnable = Runnable { removeStationWindows() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        settings = Prefs.load(this)
        syncStation()
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = Prefs.load(this)
        Prefs.registerListener(this, prefsListener)
        syncStation()
    }

    // ── Keys ──

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
        if (event.repeatCount > 0) return true // holding is handled by our own timer below

        val stream = activeStream()
        val direction = if (code == KeyEvent.KEYCODE_VOLUME_UP) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }

        val muteKey = Prefs.load(this).doublePressKey
        val isMuteKey = (muteKey == 1 && code == KeyEvent.KEYCODE_VOLUME_UP) ||
            (muteKey == 2 && code == KeyEvent.KEYCODE_VOLUME_DOWN)
        val now = SystemClock.uptimeMillis()

        if (isMuteKey && lastPressKey == code && now - lastPressTime <= DOUBLE_PRESS_MS) {
            lastPressTime = 0L
            stopRepeat()
            toggleMute(stream)
            showPanel(stream)
            return true
        }

        lastPressKey = code
        lastPressTime = now
        volumeBeforePress = audioManager.getStreamVolume(stream)
        audioManager.adjustStreamVolume(stream, direction, 0)
        showPanel(stream)
        startRepeat(code, direction)
        return true // consume the key so the system panel stays hidden
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
    private fun toggleMute(stream: Int) {
        try {
            audioManager.setStreamVolume(stream, volumeBeforePress, 0)
            val current = audioManager.getStreamVolume(stream)
            if (current > 0) {
                preMuteVolume[stream] = current
                audioManager.setStreamVolume(stream, 0, 0)
            } else {
                val restore = preMuteVolume[stream] ?: (audioManager.getStreamMaxVolume(stream) / 2)
                audioManager.setStreamVolume(stream, restore.coerceAtLeast(1), 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not toggle mute", e)
        }
    }

    private fun activeStream(): Int =
        if (audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        ) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    private fun isDndOn(): Boolean {
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        val f = nm.currentInterruptionFilter
        return f == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
            f == NotificationManager.INTERRUPTION_FILTER_NONE ||
            f == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    // ── Volume panel ──

    fun showPanel(stream: Int = activeStream()) {
        // Re-read saved settings every time, so changes made in the app apply immediately.
        settings = Prefs.load(this)
        syncStation()
        currentStream = stream
        level = audioManager.getStreamVolume(stream)
        maxLevel = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        kind = if (stream == AudioManager.STREAM_VOICE_CALL) StreamKind.CALL else StreamKind.MEDIA
        dnd = isDndOn()
        if (stationView != null) refreshStationLevels()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        if (panelView == null) addPanel() else updateLayout()
        visibleState?.targetState = true
        handler.postDelayed(hideRunnable, settings.hideDelayMs.toLong())
    }

    /** The user dragged on the bar: set the volume to that position. */
    private fun seekTo(fraction: Float) {
        val v = (fraction * maxLevel).roundToInt().coerceIn(0, maxLevel)
        try {
            audioManager.setStreamVolume(currentStream, v, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Could not set volume", e)
        }
        level = audioManager.getStreamVolume(currentStream)
    }

    /** Keep the panel on screen while a finger is on it. */
    private fun onPanelTouch(down: Boolean) {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        if (!down) handler.postDelayed(hideRunnable, settings.hideDelayMs.toLong())
    }

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

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val (screenW, screenH) = screenSizePx()
        val panelW = dp(settings.widthDp + 24)
        val panelH = dp(settings.heightDp + 24)
        // posX/posY are the panel's center; keep the whole panel inside the screen.
        val px = (settings.posX * screenW - panelW / 2f).toInt()
            .coerceIn(0, (screenW - panelW).coerceAtLeast(0))
        val py = (settings.posY * screenH - panelH / 2f).toInt()
            .coerceIn(0, (screenH - panelH).coerceAtLeast(0))

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!settings.touchEnabled) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

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
        val view = panelView ?: return
        try {
            windowManager.updateViewLayout(view, buildLayoutParams())
        } catch (e: Exception) {
            Log.e(TAG, "Could not move volume panel", e)
        }
    }

    private fun addPanel() {
        try {
            val vs = MutableTransitionState(false)
            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@VolumeAccessibilityService)
                setViewTreeSavedStateRegistryOwner(this@VolumeAccessibilityService)
                setContent {
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
                            level = level,
                            max = maxLevel,
                            kind = kind,
                            dnd = dnd,
                            modifier = Modifier.padding(12.dp),
                            onSeek = { seekTo(it) },
                            onTouch = { onPanelTouch(it) },
                        )
                    }
                }
            }
            windowManager.addView(view, buildLayoutParams())
            panelView = view
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
        val view = panelView ?: return
        panelView = null
        visibleState = null
        try {
            windowManager.removeView(view)
            view.disposeComposition()
        } catch (e: Exception) {
            Log.e(TAG, "Could not hide volume panel", e)
        }
    }

    // ── Volume Station ──

    /** Adds or removes the three-dots handle to match the saved settings. */
    private fun syncStation() {
        if (settings.stationEnabled) {
            if (handleView == null) addHandle() else repositionHandle()
        } else {
            closeStationNow()
            removeHandle()
        }
    }

    private fun handleLayoutParams(): WindowManager.LayoutParams {
        val (screenW, screenH) = screenSizePx()
        val w = dp(HANDLE_W_DP)
        val h = dp(HANDLE_H_DP)
        val px = (settings.stationX * screenW - w / 2f).toInt()
            .coerceIn(0, (screenW - w).coerceAtLeast(0))
        val py = (settings.stationY * screenH - h / 2f).toInt()
            .coerceIn(0, (screenH - h).coerceAtLeast(0))
        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = px
            this.y = py
        }
    }

    private fun addHandle() {
        try {
            val view = StationHandle(this)
            val lp = handleLayoutParams()
            windowManager.addView(view, lp)
            handleView = view
            handleLp = lp
        } catch (e: Exception) {
            Log.e(TAG, "Could not show Volume Station handle", e)
        }
    }

    private fun repositionHandle() {
        val view = handleView ?: return
        val old = handleLp ?: return
        val lp = handleLayoutParams()
        if (lp.x == old.x && lp.y == old.y) return
        handleLp = lp
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Could not move Volume Station handle", e)
        }
    }

    private fun removeHandle() {
        val view = handleView ?: return
        handleView = null
        handleLp = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "Could not remove Volume Station handle", e)
        }
    }

    /** Drag the handle with the finger (raw screen coordinates). */
    private fun moveHandleTo(x: Int, y: Int) {
        val view = handleView ?: return
        val lp = handleLp ?: return
        val (screenW, screenH) = screenSizePx()
        lp.x = x.coerceIn(0, (screenW - dp(HANDLE_W_DP)).coerceAtLeast(0))
        lp.y = y.coerceIn(0, (screenH - dp(HANDLE_H_DP)).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Could not drag Volume Station handle", e)
        }
    }

    private fun saveHandlePosition() {
        val lp = handleLp ?: return
        val (screenW, screenH) = screenSizePx()
        val fx = (lp.x + dp(HANDLE_W_DP) / 2f) / screenW
        val fy = (lp.y + dp(HANDLE_H_DP) / 2f) / screenH
        Prefs.save(
            this,
            Prefs.load(this).copy(stationX = fx.coerceIn(0f, 1f), stationY = fy.coerceIn(0f, 1f)),
        )
    }

    private fun refreshStationLevels() {
        STATION_STREAMS.forEach { (stream, _) ->
            stationMax[stream] = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            stationLevels[stream] = audioManager.getStreamVolume(stream)
        }
    }

    private fun stationLayoutParams(): WindowManager.LayoutParams {
        val (screenW, screenH) = screenSizePx()
        val rows = STATION_STREAMS.size
        val w = dp(STATION_W_DP + 2 * STATION_PAD_DP)
        val h = dp(rows * STATION_ROW_H_DP + (rows - 1) * STATION_GAP_DP + 2 * STATION_PAD_DP)
        val handle = handleLp
        val hw = dp(HANDLE_W_DP)
        val hh = dp(HANDLE_H_DP)
        val gap = dp(6)
        val hx = handle?.x ?: 0
        val hy = handle?.y ?: 0
        val handleOnLeft = hx + hw / 2 < screenW / 2
        val px = (if (handleOnLeft) hx + hw + gap else hx - w - gap)
            .coerceIn(0, (screenW - w).coerceAtLeast(0))
        val py = (hy + hh / 2 - h / 2).coerceIn(0, (screenH - h).coerceAtLeast(0))
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = px
            this.y = py
        }
    }

    private fun openStation() {
        if (stationView != null) return
        try {
            refreshStationLevels()

            // A see-through layer behind the station: tapping it closes the station.
            val scrim = View(this).apply {
                setBackgroundColor(0x26000000)
                setOnClickListener { closeStation() }
            }
            windowManager.addView(
                scrim,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ),
            )
            scrimView = scrim

            val vs = MutableTransitionState(false)
            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@VolumeAccessibilityService)
                setViewTreeSavedStateRegistryOwner(this@VolumeAccessibilityService)
                setContent {
                    AnimatedVisibility(
                        visibleState = vs,
                        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.9f),
                        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f),
                    ) {
                        StationPanel(
                            settings = settings,
                            levels = stationLevels,
                            maxes = stationMax,
                            onSeek = { stream, f -> seekStation(stream, f) },
                            onTouch = { down -> onStationTouch(down) },
                        )
                    }
                }
            }
            windowManager.addView(view, stationLayoutParams())
            stationView = view
            stationState = vs
            vs.targetState = true
            resetStationIdle()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open Volume Station", e)
            removeStationWindows()
        }
    }

    private fun seekStation(stream: Int, fraction: Float) {
        val max = stationMax[stream] ?: return
        val v = (fraction * max).roundToInt().coerceIn(0, max)
        try {
            audioManager.setStreamVolume(stream, v, 0)
        } catch (e: SecurityException) {
            // Ring and notification volumes can need "Do Not Disturb access".
            Toast.makeText(this, "الصوت ده محتاج صلاحية «عدم الإزعاج» من إعدادات التطبيق", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Could not set station volume", e)
        }
        stationLevels[stream] = audioManager.getStreamVolume(stream)
        resetStationIdle()
    }

    private fun onStationTouch(down: Boolean) {
        if (down) handler.removeCallbacks(closeStationRunnable) else resetStationIdle()
    }

    private fun resetStationIdle() {
        handler.removeCallbacks(closeStationRunnable)
        handler.postDelayed(closeStationRunnable, STATION_IDLE_MS)
    }

    /** Closes with an animation. The see-through layer goes away right away. */
    private fun closeStation() {
        handler.removeCallbacks(closeStationRunnable)
        val vs = stationState ?: return
        scrimView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Could not remove station layer", e)
            }
        }
        scrimView = null
        vs.targetState = false
        handler.removeCallbacks(removeStationRunnable)
        handler.postDelayed(removeStationRunnable, 180L)
    }

    private fun closeStationNow() {
        handler.removeCallbacks(closeStationRunnable)
        handler.removeCallbacks(removeStationRunnable)
        removeStationWindows()
    }

    private fun removeStationWindows() {
        scrimView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Could not remove station layer", e)
            }
        }
        scrimView = null
        stationView?.let {
            try {
                windowManager.removeView(it)
                it.disposeComposition()
            } catch (e: Exception) {
                Log.e(TAG, "Could not remove station", e)
            }
        }
        stationView = null
        stationState = null
    }

    /** The three-dots handle: tap to open the station, drag to move it. */
    private inner class StationHandle(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(dp(HANDLE_W_DP), dp(HANDLE_H_DP))
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, w / 2f, w / 2f, bgPaint)
            for (i in 1..3) {
                canvas.drawCircle(w / 2f, h / 4f * i, dp(3).toFloat(), dotPaint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = handleLp?.x ?: 0
                    startY = handleLp?.y ?: 0
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) moveHandleTo(startX + dx.toInt(), startY + dy.toInt())
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) saveHandlePosition() else openStation()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) saveHandlePosition()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        Prefs.unregisterListener(this, prefsListener)
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        stopRepeat()
        removePanel()
        closeStationNow()
        removeHandle()
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

// ───────────────────────────── Settings screen ─────────────────────────────

private val SWATCHES = listOf(
    0xFF1E1E1E, 0xFF000000, 0xFFFFFFFF, 0xFF1565C0,
    0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A, 0xFFEF6C00,
).map { it.toInt() }

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit,
) {
    Text(label)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorRow(
    title: String,
    selected: Int,
    showAuto: Boolean,
    onPick: (Int) -> Unit,
) {
    Text(title)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAuto) {
            FilterChip(
                selected = selected == 0,
                onClick = { onPick(0) },
                label = { Text("تلقائي") },
            )
        }
        SWATCHES.forEach { argb ->
            val isSelected = selected == argb
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(
                        BorderStroke(3.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray),
                        CircleShape,
                    )
                    .clickable { onPick(argb) },
            )
        }
    }
}

/**
 * A mini phone screen: tap or drag on it to place something. [posX]/[posY] are the center of
 * the marker as fractions of the screen; the marker is drawn at its real relative size.
 */
@Composable
private fun PositionPicker(
    posX: Float,
    posY: Float,
    markerWdp: Int,
    markerHdp: Int,
    cornerDp: Int,
    markerColor: Color,
    onChange: (Float, Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val config = LocalConfiguration.current
    val screenWdp = config.screenWidthDp.coerceAtLeast(1)
    val screenHdp = config.screenHeightDp.coerceAtLeast(1)
    val pickerW = 180.dp
    val pickerH = pickerW * (screenHdp.toFloat() / screenWdp)
    val scale = 180f / screenWdp
    val markerW = (markerWdp * scale).dp
    val markerH = (markerHdp * scale).dp
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .size(pickerW, pickerH)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), shape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o ->
                    currentOnChange(o.x / size.width, o.y / size.height)
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> currentOnChange(o.x / size.width, o.y / size.height) },
                    onDrag = { c, _ ->
                        c.consume()
                        currentOnChange(c.position.x / size.width, c.position.y / size.height)
                    },
                )
            },
    ) {
        val maxLeft = (pickerW - markerW).coerceAtLeast(0.dp)
        val maxTop = (pickerH - markerH).coerceAtLeast(0.dp)
        val left = (pickerW * posX - markerW / 2).coerceIn(0.dp, maxLeft)
        val top = (pickerH * posY - markerH / 2).coerceIn(0.dp, maxTop)
        Box(
            modifier = Modifier
                .offset(left, top)
                .size(markerW, markerH)
                .clip(RoundedCornerShape((cornerDp * scale).dp))
                .background(markerColor),
        )
    }
}

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)
    private var dndAccess by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
        dndAccess = getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted ?: true
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val cn = ComponentName(this, VolumeAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) ||
                it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    @Composable
    private fun SettingsScreen() {
        var settings by remember { mutableStateOf(Prefs.load(this@MainActivity)) }
        var previewLevel by remember { mutableIntStateOf(13) }
        var previewDnd by remember { mutableStateOf(false) }

        // Every change is saved immediately.
        fun update(new: PanelSettings) {
            settings = new
            Prefs.save(this@MainActivity, new)
        }

        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Kanade System", style = MaterialTheme.typography.headlineSmall)

                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    VolumePanel(settings, previewLevel, 20, StreamKind.MEDIA, previewDnd)
                }

                LabeledSlider("مستوى المعاينة: $previewLevel / 20", previewLevel, 0f..15f) {
                    previewLevel = it
                }
                SwitchRow("معاينة أيقونة عدم الإزعاج", previewDnd) { previewDnd = it }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (serviceEnabled) "الخدمة شغالة ✅" else "الخدمة مش مفعّلة ❌")
                        Button(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("افتح إعدادات إمكانية الوصول") }
                        Button(onClick = {
                            val s = VolumeAccessibilityService.instance
                            if (s != null) s.showPanel()
                            else Toast.makeText(this@MainActivity, "فعّل الخدمة الأول", Toast.LENGTH_SHORT).show()
                        }) { Text("جرّب اللوحة") }
                    }
                }

                Text("مكان اللوحة (اضغط أو اسحب على الشاشة المصغّرة)")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PositionPicker(
                        posX = settings.posX,
                        posY = settings.posY,
                        markerWdp = settings.widthDp + 24,
                        markerHdp = settings.heightDp + 24,
                        cornerDp = settings.cornerRadiusDp,
                        markerColor = if (settings.showFrame) Color(settings.colorArgb)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    ) { fx, fy ->
                        update(settings.copy(posX = fx.coerceIn(0f, 1f), posY = fy.coerceIn(0f, 1f)))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { update(settings.copy(posX = 0.5f, posY = 0.08f)) },
                        label = { Text("أعلى") },
                    )
                    AssistChip(
                        onClick = { update(settings.copy(posX = 0.5f, posY = 0.5f)) },
                        label = { Text("المنتصف") },
                    )
                    AssistChip(
                        onClick = { update(settings.copy(posX = 0.5f, posY = 0.92f)) },
                        label = { Text("أسفل") },
                    )
                }

                SwitchRow("اللوحة رأسية", settings.vertical) {
                    // Rotate the panel: swap its width and height.
                    update(settings.copy(vertical = it, widthDp = settings.heightDp, heightDp = settings.widthDp))
                }
                SwitchRow("التحكم باللمس (اسحب على الشريط)", settings.touchEnabled) {
                    update(settings.copy(touchEnabled = it))
                }

                Text("ضغطتين متتاليتين = كتم")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.doublePressKey == 0,
                        onClick = { update(settings.copy(doublePressKey = 0)) },
                        label = { Text("بدون") },
                    )
                    FilterChip(
                        selected = settings.doublePressKey == 1,
                        onClick = { update(settings.copy(doublePressKey = 1)) },
                        label = { Text("زر رفع الصوت") },
                    )
                    FilterChip(
                        selected = settings.doublePressKey == 2,
                        onClick = { update(settings.copy(doublePressKey = 2)) },
                        label = { Text("زر خفض الصوت") },
                    )
                }

                SwitchRow("إظهار الإطار الخارجي", settings.showFrame) {
                    update(settings.copy(showFrame = it))
                }
                SwitchRow("إظهار أيقونة عدم الإزعاج", settings.showDndIcon) {
                    update(settings.copy(showDndIcon = it))
                }

                LabeledSlider("استدارة الحواف: ${settings.cornerRadiusDp} %", settings.cornerRadiusDp, 0f..40f) {
                    update(settings.copy(cornerRadiusDp = it))
                }
                LabeledSlider(
                    "عرض اللوحة: ${settings.widthDp} %",
                    settings.widthDp,
                    if (settings.vertical) 32f..120f else 160f..360f,
                ) {
                    update(settings.copy(widthDp = it))
                }
                LabeledSlider(
                    "طول اللوحة: ${settings.heightDp} dp",
                    settings.heightDp,
                    if (settings.vertical) 160f..360f else 32f..120f,
                ) {
                    update(settings.copy(heightDp = it))
                }
                LabeledSlider(
                    "تأخير الاختفاء: ${settings.hideDelayMs / 1000f} ث",
                    settings.hideDelayMs,
                    500f..5000f,
                ) {
                    update(settings.copy(hideDelayMs = it / 100 * 100))
                }
                LabeledSlider(
                    "الأيقونة تحمرّ عند: ${settings.redThresholdPct}%",
                    settings.redThresholdPct,
                    50f..100f,
                ) {
                    update(settings.copy(redThresholdPct = it))
                }

                ColorRow("لون اللوحة", settings.colorArgb, showAuto = false) {
                    update(settings.copy(colorArgb = it))
                }
                ColorRow("لون الشريط", settings.barColorArgb, showAuto = true) {
                    update(settings.copy(barColorArgb = it))
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Volume Station", style = MaterialTheme.typography.titleMedium)
                        Text("3 نقاط على الشاشة، تضغط عليها فتفتح كل الأصوات (ميديا، مكالمة، رنين، إشعارات، منبّه).")
                        SwitchRow("تفعيل Volume Station", settings.stationEnabled) {
                            update(settings.copy(stationEnabled = it))
                        }
                        if (settings.stationEnabled) {
                            Text("مكان النقاط (اضغط أو اسحب هنا، أو اسحبها على الشاشة نفسها)")
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                PositionPicker(
                                    posX = settings.stationX,
                                    posY = settings.stationY,
                                    markerWdp = HANDLE_W_DP,
                                    markerHdp = HANDLE_H_DP,
                                    cornerDp = HANDLE_W_DP / 2,
                                    markerColor = Color(0xB3000000),
                                ) { fx, fy ->
                                    update(settings.copy(stationX = fx.coerceIn(0f, 1f), stationY = fy.coerceIn(0f, 1f)))
                                }
                            }
                        }
                        if (!dndAccess) {
                            Text("الرنين والإشعارات محتاجين صلاحية «الوصول لعدم الإزعاج» عشان تتحكم فيهم.")
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            }) { Text("امنح صلاحية عدم الإزعاج") }
                        }
                    }
                }
            }
        }
    }
}
