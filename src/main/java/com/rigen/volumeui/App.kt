package com.rigen.volumeui

import android.accessibilityservice.AccessibilityService
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
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

data class PanelSettings(
    val cornerRadiusDp: Int = 24,
    val colorArgb: Int = 0xFF1E1E1E.toInt(),
    val widthDp: Int = 260,
)

/** Single source of truth for saved settings. Used by both the app screen and the service. */
object Prefs {
    private const val FILE = "rigen_volume_ui"
    private const val K_RADIUS = "corner_radius_dp"
    private const val K_COLOR = "color_argb"
    private const val K_WIDTH = "width_dp"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): PanelSettings {
        val d = PanelSettings()
        val p = sp(context)
        return PanelSettings(
            cornerRadiusDp = p.getInt(K_RADIUS, d.cornerRadiusDp),
            colorArgb = p.getInt(K_COLOR, d.colorArgb),
            widthDp = p.getInt(K_WIDTH, d.widthDp),
        )
    }

    fun save(context: Context, s: PanelSettings) {
        sp(context).edit()
            .putInt(K_RADIUS, s.cornerRadiusDp)
            .putInt(K_COLOR, s.colorArgb)
            .putInt(K_WIDTH, s.widthDp)
            .apply()
    }
}

/** The volume panel itself. Used for the in-app preview and for the real overlay. */
@Composable
fun VolumePanel(settings: PanelSettings, level: Int, max: Int, modifier: Modifier = Modifier) {
    val bg = Color(settings.colorArgb)
    val fg = if (bg.luminance() > 0.5f) Color.Black else Color.White
    val fraction = if (max > 0) (level.toFloat() / max).coerceIn(0f, 1f) else 0f

    Row(
        modifier = modifier
            .width(settings.widthDp.dp)
            .clip(RoundedCornerShape(settings.cornerRadiusDp.dp))
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${(fraction * 100).roundToInt()}",
            color = fg,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(fg.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(fg),
            )
        }
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
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager

    private var panelView: ComposeView? = null
    private var level by mutableIntStateOf(0)
    private var maxLevel by mutableIntStateOf(1)
    private var settings by mutableStateOf(PanelSettings())

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

    fun showPanel(stream: Int = activeStream()) {
        // Re-read saved settings every time, so changes made in the app apply immediately.
        settings = Prefs.load(this)
        level = audioManager.getStreamVolume(stream)
        maxLevel = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        if (panelView == null) addPanel()
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun addPanel() {
        try {
            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@VolumeAccessibilityService)
                setViewTreeSavedStateRegistryOwner(this@VolumeAccessibilityService)
                setContent { VolumePanel(settings, level, maxLevel) }
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
                y = (48 * resources.displayMetrics.density).toInt()
            }
            windowManager.addView(view, lp)
            panelView = view
        } catch (e: Exception) {
            Log.e(TAG, "Could not show volume panel", e)
        }
    }

    private fun hidePanel() {
        val view = panelView ?: return
        panelView = null
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
        hidePanel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RigenVolumeUI"
        private const val HIDE_DELAY_MS = 1500L

        @Volatile
        var instance: VolumeAccessibilityService? = null
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
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    VolumePanel(settings = settings, level = 7, max = 15)
                }

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

                Text("استدارة الحواف: ${settings.cornerRadiusDp} dp")
                Slider(
                    value = settings.cornerRadiusDp.toFloat(),
                    onValueChange = { update(settings.copy(cornerRadiusDp = it.roundToInt())) },
                    valueRange = 0f..40f,
                )

                Text("عرض اللوحة: ${settings.widthDp} dp")
                Slider(
                    value = settings.widthDp.toFloat(),
                    onValueChange = { update(settings.copy(widthDp = it.roundToInt())) },
                    valueRange = 160f..360f,
                )

                Text("اللون")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SWATCHES.forEach { argb ->
                        val selected = settings.colorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    BorderStroke(3.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray),
                                    CircleShape,
                                )
                                .clickable { update(settings.copy(colorArgb = argb)) },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val SWATCHES = listOf(
            0xFF1E1E1E, 0xFF000000, 0xFFFFFFFF, 0xFF1565C0,
            0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A, 0xFFEF6C00,
        ).map { it.toInt() }
    }
}
