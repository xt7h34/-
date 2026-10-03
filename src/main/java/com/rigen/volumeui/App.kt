package com.rigen.volumeui

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.ComposeView
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
import kotlin.math.roundToInt

// ───────────────────────────── Settings ─────────────────────────────

data class PanelSettings(
    val cornerRadiusDp: Int = 24,
    val colorArgb: Int = 0xFF1E1E1E.toInt(),
    /** Bar color. 0 means "automatic" (picked for contrast with the panel color). */
    val barColorArgb: Int = 0,
    val widthDp: Int = 260,
    val heightDp: Int = 56,
    val showFrame: Boolean = true,
    val hideDelayMs: Int = 1500,
    val redThresholdPct: Int = 85,
    val showDndIcon: Boolean = true,
)

/** Single source of truth for saved settings. Used by both the app screen and the service. */
object Prefs {
    private const val FILE = "kanade_system"
    private const val K_RADIUS = "corner_radius_dp"
    private const val K_COLOR = "color_argb"
    private const val K_BAR_COLOR = "bar_color_argb"
    private const val K_WIDTH = "width_dp"
    private const val K_HEIGHT = "height_dp"
    private const val K_FRAME = "show_frame"
    private const val K_DELAY = "hide_delay_ms"
    private const val K_RED = "red_threshold_pct"
    private const val K_DND = "show_dnd_icon"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): PanelSettings {
        val d = PanelSettings()
        val p = sp(context)
        return PanelSettings(
            cornerRadiusDp = p.getInt(K_RADIUS, d.cornerRadiusDp),
            colorArgb = p.getInt(K_COLOR, d.colorArgb),
            barColorArgb = p.getInt(K_BAR_COLOR, d.barColorArgb),
            widthDp = p.getInt(K_WIDTH, d.widthDp),
            heightDp = p.getInt(K_HEIGHT, d.heightDp),
            showFrame = p.getBoolean(K_FRAME, d.showFrame),
            hideDelayMs = p.getInt(K_DELAY, d.hideDelayMs),
            redThresholdPct = p.getInt(K_RED, d.redThresholdPct),
            showDndIcon = p.getBoolean(K_DND, d.showDndIcon),
        )
    }

    fun save(context: Context, s: PanelSettings) {
        sp(context).edit()
            .putInt(K_RADIUS, s.cornerRadiusDp)
            .putInt(K_COLOR, s.colorArgb)
            .putInt(K_BAR_COLOR, s.barColorArgb)
            .putInt(K_WIDTH, s.widthDp)
            .putInt(K_HEIGHT, s.heightDp)
            .putBoolean(K_FRAME, s.showFrame)
            .putInt(K_DELAY, s.hideDelayMs)
            .putInt(K_RED, s.redThresholdPct)
            .putBoolean(K_DND, s.showDndIcon)
            .apply()
    }
}

// ───────────────────────────── Icons & panel ─────────────────────────────

/** Small built-in icons, drawn from 24x24 vector path data (no extra library needed). */
enum class StreamKind(val pathData: String) {
    MEDIA("M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"),
    CALL("M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"),
    MUTED("M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z"),
    DND("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13H7v-2h10v2z"),
}

private val HIGH_VOLUME_RED = Color(0xFFE53935)

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

/** The volume panel itself. Used for the in-app preview and for the real overlay. */
@Composable
fun VolumePanel(
    settings: PanelSettings,
    level: Int,
    max: Int,
    kind: StreamKind,
    dnd: Boolean,
    modifier: Modifier = Modifier,
) {
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
    val thickness = (settings.heightDp / 6).coerceIn(4, 16)
    val iconSize = (settings.heightDp - 24).coerceIn(16, 32)

    var shell = modifier.width(settings.widthDp.dp).height(settings.heightDp.dp)
    if (settings.showFrame) {
        shell = shell
            .clip(RoundedCornerShape(settings.cornerRadiusDp.dp))
            .background(frameBg)
    }

    Row(
        modifier = shell.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreamIcon(iconKind, iconColor, iconSize.dp)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(thickness.dp)
                .clip(RoundedCornerShape((thickness / 2).dp))
                .background(barColor.copy(alpha = 0.25f)),
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

    private var panelView: ComposeView? = null
    private var visibleState: MutableTransitionState<Boolean>? = null
    private var level by mutableIntStateOf(0)
    private var maxLevel by mutableIntStateOf(1)
    private var settings by mutableStateOf(PanelSettings())
    private var kind by mutableStateOf(StreamKind.MEDIA)
    private var dnd by mutableStateOf(false)

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
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val direction = if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }
            val stream = activeStream()
            audioManager.adjustStreamVolume(stream, direction, 0)
            showPanel(stream)
        }
        return true // consume the key so the system panel stays hidden
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

    fun showPanel(stream: Int = activeStream()) {
        // Re-read saved settings every time, so changes made in the app apply immediately.
        settings = Prefs.load(this)
        level = audioManager.getStreamVolume(stream)
        maxLevel = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        kind = if (stream == AudioManager.STREAM_VOICE_CALL) StreamKind.CALL else StreamKind.MEDIA
        dnd = isDndOn()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
        if (panelView == null) addPanel()
        visibleState?.targetState = true
        handler.postDelayed(hideRunnable, settings.hideDelayMs.toLong())
    }

    private fun addPanel() {
        try {
            val vs = MutableTransitionState(false)
            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@VolumeAccessibilityService)
                setViewTreeSavedStateRegistryOwner(this@VolumeAccessibilityService)
                setContent {
                    AnimatedVisibility(
                        visibleState = vs,
                        enter = fadeIn(tween(200)) +
                            scaleIn(tween(200), initialScale = 0.85f) +
                            slideInVertically(tween(200)) { -it / 3 },
                        exit = fadeOut(tween(160)) +
                            scaleOut(tween(160), targetScale = 0.9f) +
                            slideOutVertically(tween(160)) { -it / 3 },
                    ) {
                        VolumePanel(settings, level, maxLevel, kind, dnd, Modifier.padding(12.dp))
                    }
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // Needs no "draw over other apps" permission, unlike TYPE_APPLICATION_OVERLAY.
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (36 * resources.displayMetrics.density).toInt()
            }
            windowManager.addView(view, lp)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(removeRunnable)
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

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)

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
                    VolumePanel(settings, previewLevel, 15, StreamKind.MEDIA, previewDnd)
                }

                LabeledSlider("مستوى المعاينة: $previewLevel / 15", previewLevel, 0f..15f) {
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

                SwitchRow("إظهار الإطار الخارجي", settings.showFrame) {
                    update(settings.copy(showFrame = it))
                }
                SwitchRow("إظهار أيقونة عدم الإزعاج", settings.showDndIcon) {
                    update(settings.copy(showDndIcon = it))
                }

                LabeledSlider("استدارة الحواف: ${settings.cornerRadiusDp} dp", settings.cornerRadiusDp, 0f..40f) {
                    update(settings.copy(cornerRadiusDp = it))
                }
                LabeledSlider("عرض اللوحة: ${settings.widthDp} dp", settings.widthDp, 160f..360f) {
                    update(settings.copy(widthDp = it))
                }
                LabeledSlider("طول اللوحة: ${settings.heightDp} dp", settings.heightDp, 32f..120f) {
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
            }
        }
    }
}
