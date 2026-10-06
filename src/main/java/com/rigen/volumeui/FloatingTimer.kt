package com.rigen.volumeui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

/**
 * The small timer that floats over other apps. It lives in the accessibility service (so it needs
 * no "draw over other apps" permission), shows only while another app is in front, can be dragged,
 * and can be hidden with its x without cancelling the timer.
 */
class FloatingTimer(private val service: VolumeAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var compose: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var otherAppInFront = false
    private var lastX = -1
    private var lastY = -1

    fun onForeground(pkg: String) {
        otherAppInFront = pkg != service.packageName
        refresh()
    }

    fun refresh() {
        val t = ClockStore.timer(service)
        val should = otherAppInFront && t.active &&
            ClockStore.floatingEnabled(service) && !ClockStore.floatingHidden(service)
        if (should) show() else remove()
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    private fun show() {
        if (root != null) return
        try {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = if (lastX >= 0) lastX else dp(16)
            lp.y = if (lastY >= 0) lastY else dp(140)

            val view = ComposeView(service).apply {
                setViewTreeLifecycleOwner(service)
                setViewTreeSavedStateRegistryOwner(service)
                setContent {
                    FloatingPill(
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onOpen = { openTimer() },
                        onHide = {
                            ClockStore.setFloatingHidden(service, true)
                            main.post { remove() }
                        },
                    )
                }
            }
            val container = FrameLayout(service)
            container.setViewTreeLifecycleOwner(service)
            container.setViewTreeSavedStateRegistryOwner(service)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            wm.addView(container, lp)
            root = container
            compose = view
            params = lp
        } catch (e: Exception) {
            Diag.error(service, "Could not show the floating timer", e)
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val r = root ?: return
        val lp = params ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        lastX = lp.x
        lastY = lp.y
        try {
            wm.updateViewLayout(r, lp)
        } catch (e: Exception) {
            // The window is already gone.
        }
    }

    private fun openTimer() {
        try {
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .putExtra(EXTRA_PAGE, PAGE_TIMER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        } catch (e: Exception) {
            Diag.error(service, "Could not open the timer", e)
        }
    }

    fun remove() {
        val r = root ?: return
        val c = compose
        root = null
        compose = null
        params = null
        try {
            wm.removeView(r)
            c?.disposeComposition()
        } catch (e: Exception) {
            Diag.error(service, "Could not hide the floating timer", e)
        }
    }
}

@Composable
private fun FloatingPill(onDrag: (Float, Float) -> Unit, onOpen: () -> Unit, onHide: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var state by remember { mutableStateOf(ClockStore.timer(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            state = ClockStore.timer(ctx)
            delay(250)
        }
    }
    Row(
        modifier = Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xEB16161B))
            .pointerInput(Unit) { detectTapGestures(onTap = { onOpen() }) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathIcon(Glyph.TIMER.pathData, Color(0xFFFFB27A), 18.dp, Modifier.padding(start = 14.dp))
        Text(
            formatMs(state.remaining(now)),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        )
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF))
                .clickable { ClockEngine.timerToggle(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            PathIcon(
                (if (state.running) Glyph.PAUSE else Glyph.PLAY).pathData,
                Color.White, 18.dp,
            )
        }
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .size(34.dp)
                .clip(CircleShape)
                .clickable { onHide() },
            contentAlignment = Alignment.Center,
        ) {
            PathIcon(Glyph.CLOSE.pathData, Color(0xCCFFFFFF), 16.dp)
        }
    }
}
