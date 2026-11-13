package com.rigen.volumeui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.roundToInt

/** Settings of the Edge Panel (kept apart from the volume panel's own settings). */
object EdgePrefs {
    private fun sp(c: Context) = c.getSharedPreferences("edge_panel", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** False = the right edge of the screen, true = the left edge. */
    fun left(c: Context): Boolean = sp(c).getBoolean("left", false)
    fun setLeft(c: Context, v: Boolean) = sp(c).edit().putBoolean("left", v).apply()
}

/**
 * A panel that slides out of the screen edge over any app. A thin strip on the edge catches the
 * swipe, then a black panel (shaped like it grows out of the edge) shows pages you swipe between:
 * for now the volume slider and the ringer mode. It lives in the accessibility service, so it
 * needs no "draw over other apps" permission.
 */
class EdgePanel(private val service: VolumeAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val controller = VolumeController(service)

    private var strip: FrameLayout? = null
    private var stripLeft = false
    private var panelRoot: FrameLayout? = null

    private var visible by mutableStateOf(false)
    private var page by mutableIntStateOf(0)
    private var level by mutableIntStateOf(0)
    private var maxLevel by mutableIntStateOf(1)
    private var ringer by mutableIntStateOf(AudioManager.RINGER_MODE_NORMAL)
    private var limits: List<Int> = DEFAULT_LIMITS
    private var hapticsOn = true

    private val closeRunnable = Runnable { close() }
    private val removeRunnable = Runnable { removePanel() }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    /** Shows or hides the strip to match the settings. Safe to call at any time. */
    fun refresh() {
        if (!EdgePrefs.enabled(service)) {
            remove()
            return
        }
        val left = EdgePrefs.left(service)
        if (strip != null && stripLeft != left) {
            removePanel()
            removeStrip()
        }
        showStrip(left)
    }

    fun remove() {
        main.removeCallbacks(closeRunnable)
        removePanel()
        removeStrip()
    }

    // ───────────── The strip on the screen edge ─────────────

    private fun showStrip(left: Boolean) {
        if (strip != null) return
        try {
            val side = if (left) Gravity.START else Gravity.END
            val lp = WindowManager.LayoutParams(
                dp(18),
                dp(200),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = side or Gravity.CENTER_VERTICAL

            val container = FrameLayout(service)
            val handle = View(service).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(0x66808080)
                }
            }
            container.addView(
                handle,
                FrameLayout.LayoutParams(dp(3), dp(56), side or Gravity.CENTER_VERTICAL),
            )

            // Keep Android's own back swipe away from this strip, or it would win every time.
            if (Build.VERSION.SDK_INT >= 29) {
                container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                }
            }

            var downX = 0f
            var opened = false
            container.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        opened = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = if (left) e.rawX - downX else downX - e.rawX
                        if (!opened && dx > dp(22)) {
                            opened = true
                            open()
                        }
                    }
                }
                true
            }
            wm.addView(container, lp)
            strip = container
            stripLeft = left
        } catch (e: Exception) {
            Diag.error(service, "Could not show the edge strip", e)
        }
    }

    private fun removeStrip() {
        strip?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        strip = null
    }

    // ───────────── The panel ─────────────

    private fun open() {
        if (panelRoot != null) {
            // Still closing, or already open: just bring it back.
            main.removeCallbacks(removeRunnable)
            visible = true
            resetIdle()
            return
        }
        try {
            val settings = Prefs.load(service)
            limits = settings.volumeLimits
            hapticsOn = settings.haptics
            maxLevel = controller.max(AudioManager.STREAM_MUSIC)
            level = controller.level(AudioManager.STREAM_MUSIC)
            ringer = controller.ringerMode()
            val left = stripLeft

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL

            val root = EdgeRoot(service, onOutside = { close() }, onActivity = { resetIdle() })
            root.setViewTreeLifecycleOwner(service)
            root.setViewTreeSavedStateRegistryOwner(service)
            val view = ComposeView(service).apply {
                setViewTreeLifecycleOwner(service)
                setViewTreeSavedStateRegistryOwner(service)
                setContent {
                    EdgePanelContent(
                        left = left,
                        visible = visible,
                        page = page,
                        onPage = { page = it.coerceIn(0, PAGE_COUNT - 1) },
                        level = level,
                        maxLevel = maxLevel,
                        onSeek = { seek(it) },
                        ringer = ringer,
                        onRinger = { setRinger(it) },
                    )
                }
            }
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            visible = false
            wm.addView(root, lp)
            panelRoot = root
            // A moment later, so the slide-in is animated instead of starting already open.
            main.postDelayed({ visible = true }, 30)
            haptic()
            resetIdle()
        } catch (e: Exception) {
            Diag.error(service, "Could not show the edge panel", e)
            removePanel()
        }
    }

    private fun close() {
        main.removeCallbacks(closeRunnable)
        if (panelRoot == null) return
        visible = false
        main.removeCallbacks(removeRunnable)
        main.postDelayed(removeRunnable, 260)
    }

    private fun removePanel() {
        main.removeCallbacks(removeRunnable)
        panelRoot?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        panelRoot = null
        visible = false
        page = 0
    }

    private fun resetIdle() {
        main.removeCallbacks(closeRunnable)
        main.postDelayed(closeRunnable, IDLE_MS)
    }

    // ───────────── Actions ─────────────

    private fun seek(fraction: Float) {
        try {
            val target = (fraction.coerceIn(0f, 1f) * maxLevel).roundToInt()
            val before = level
            level = controller.setLevel(AudioManager.STREAM_MUSIC, target, limits)
            if (level != before) haptic()
        } catch (e: Exception) {
            Diag.error(service, "Edge panel volume", e)
        }
    }

    private fun setRinger(mode: Int) {
        try {
            try {
                controller.setRingerMode(mode)
            } catch (e: SecurityException) {
                // Silent needs "Do Not Disturb access".
                Toast.makeText(
                    service,
                    Prefs.localized(service).getString(R.string.toast_dnd_needed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            ringer = controller.ringerMode()
            haptic()
        } catch (e: Exception) {
            Diag.error(service, "Edge panel ringer", e)
        }
    }

    private fun haptic() {
        if (!hapticsOn) return
        try {
            Haptics.tick(service)
        } catch (e: Exception) {
            // A missed tick is not worth an error.
        }
    }

    private companion object {
        const val PAGE_COUNT = 2
        const val IDLE_MS = 4000L
    }
}

/** Catches taps outside the panel (to close it) and any touch inside (to keep it open). */
private class EdgeRoot(
    context: Context,
    private val onOutside: () -> Unit,
    private val onActivity: () -> Unit,
) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        onActivity()
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * The black shape that looks like it grows out of the screen edge: the side on the edge flows
 * into the body with a smooth curve at the top and the bottom.
 */
private class EdgeShape(private val left: Boolean, private val insetDp: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val inset = insetDp * density.density
        val fillet = minOf(h / 2f, 46f * density.density)
        fun x(v: Float) = if (left) w - v else v
        val p = Path()
        p.moveTo(x(w), 0f)
        p.cubicTo(x(w * 0.62f), 0f, x(inset), fillet * 0.35f, x(inset), fillet)
        p.lineTo(x(inset), h - fillet)
        p.cubicTo(x(inset), h - fillet * 0.35f, x(w * 0.62f), h, x(w), h)
        p.close()
        return Outline.Generic(p)
    }
}

private val PanelTrack = Color(0xFF3A3A3C)
private val PanelButton = Color(0xFF2C2C2E)
private val PanelMuted = Color(0xFFAEAEB2)
private val PanelRed = Color(0xFFFF453A)

@Composable
private fun EdgePanelContent(
    left: Boolean,
    visible: Boolean,
    page: Int,
    onPage: (Int) -> Unit,
    level: Int,
    maxLevel: Int,
    onSeek: (Float) -> Unit,
    ringer: Int,
    onRinger: (Int) -> Unit,
) {
    val t by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "edge_in")
    val height by animateDpAsState(if (page == 0) 300.dp else 222.dp, tween(260), label = "edge_h")
    val inset = 24.dp

    Box(
        Modifier
            .width(124.dp)
            .height(height)
            .graphicsLayer {
                translationX = (1f - t) * size.width * (if (left) -1f else 1f)
                alpha = t
            }
            .background(Color.Black, EdgeShape(left, inset.value))
            .pointerInput(page) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                    onDragEnd = {
                        if (abs(total) > 60f) onPage(if (total < 0f) page + 1 else page - 1)
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = if (left) 0.dp else inset,
                    end = if (left) inset else 0.dp,
                    bottom = 20.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (page == 0) {
                VolumePage(level, maxLevel, onSeek)
            } else {
                RingerPage(ringer, onRinger)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = if (left) 0.dp else inset,
                    end = if (left) inset else 0.dp,
                    bottom = 8.dp,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until 2) {
                    Box(
                        Modifier
                            .size(if (i == page) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (i == page) Color.White else Color(0xFF636366))
                            .clickable { onPage(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumePage(level: Int, maxLevel: Int, onSeek: (Float) -> Unit) {
    val fraction = level.toFloat() / maxLevel.coerceAtLeast(1)
    val shown by animateFloatAsState(fraction, tween(90), label = "edge_vol")
    Box(
        Modifier
            .size(64.dp, 220.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(PanelTrack)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onSeek(1f - down.position.y / size.height)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        onSeek(1f - change.position.y / size.height)
                        change.consume()
                    }
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(shown.coerceIn(0f, 1f))
                .background(Color.White),
        )
        PathIcon(
            pathData = (if (level == 0) Glyph.MUTED else Glyph.VOLUME).pathData,
            color = if (shown > 0.14f) PanelTrack else Color.White,
            iconSize = 26.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
    }
}

@Composable
private fun RingerPage(ringer: Int, onRinger: (Int) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RingerItem(Glyph.VOLUME, ringer == AudioManager.RINGER_MODE_NORMAL, PanelMuted) {
            onRinger(AudioManager.RINGER_MODE_NORMAL)
        }
        RingerItem(Glyph.VIBRATE, ringer == AudioManager.RINGER_MODE_VIBRATE, PanelMuted) {
            onRinger(AudioManager.RINGER_MODE_VIBRATE)
        }
        RingerItem(Glyph.MUTED, ringer == AudioManager.RINGER_MODE_SILENT, PanelRed) {
            onRinger(AudioManager.RINGER_MODE_SILENT)
        }
    }
}

@Composable
private fun RingerItem(glyph: Glyph, selected: Boolean, accent: Color, onTap: () -> Unit) {
    val tint = when {
        selected && accent == PanelRed -> PanelRed
        selected -> Color(0xFF1C1C1E)
        else -> accent
    }
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else PanelButton)
            .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        PathIcon(glyph.pathData, tint, 24.dp)
    }
}
