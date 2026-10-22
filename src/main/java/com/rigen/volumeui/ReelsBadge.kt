package com.rigen.volumeui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The brain with today's counter that floats at the top of the screen while a counted app (Instagram,
 * YouTube...) is in front. It lives in the accessibility service like the floating timer, so it needs
 * no "draw over other apps" permission. It can be dragged, and a tap opens the Reels page.
 */
class ReelsBadge(private val service: VolumeAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var compose: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var frontKey: String? = null
    private var lastX = 0
    private var lastY = -1

    fun onForeground(pkg: String) {
        frontKey = ReelsStore.appOf(pkg)?.key
        refresh()
    }

    fun refresh() {
        val key = frontKey
        val should = key != null &&
            ReelsStore.enabled(service) &&
            ReelsStore.overlay(service) &&
            ReelsStore.apps(service).contains(key)
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
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.x = lastX
            lp.y = if (lastY >= 0) lastY else dp(10)

            val view = ComposeView(service).apply {
                setViewTreeLifecycleOwner(service)
                setViewTreeSavedStateRegistryOwner(service)
                setContent {
                    ReelsBadgeContent(
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onOpen = { openReels() },
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
            Diag.error(service, "Could not show the reels badge", e)
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

    private fun openReels() {
        try {
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .putExtra(EXTRA_PAGE, PAGE_REELS)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        } catch (e: Exception) {
            Diag.error(service, "Could not open the reels page", e)
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
            Diag.error(service, "Could not hide the reels badge", e)
        }
    }
}

@Composable
private fun ReelsBadgeContent(onDrag: (Float, Float) -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val t = ReelsStore.tick // read so the badge redraws on every new count
    val today = remember(t) { ReelsStore.today(ctx) }
    val limit = ReelsStore.limit(ctx)
    val level = reelsLevel(today.total, limit)
    val remaining = limit - today.total

    // A small pop each time the count goes up.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(today.total) {
        pop.snapTo(1.25f)
        pop.animateTo(1f, tween(220))
    }

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onOpen() })
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }
                    .clip(CircleShape)
                    .background(Color(0xCC1B0F12)),
                contentAlignment = Alignment.Center,
            ) {
                ReelsBrainFace(level, Modifier.size(38.dp))
            }
            Box(
                modifier = Modifier
                    .offset(y = (-8).dp)
                    .clip(RoundedCornerShape(50))
                    .background(reelsLevelColor(level))
                    .padding(horizontal = 11.dp, vertical = 2.dp),
            ) {
                Text(
                    text = today.total.toString(),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            // A heads-up when the limit is close.
            if (remaining in 1..maxOf(5, limit / 10)) {
                Text(
                    text = stringResource(R.string.reels_left_n, remaining),
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.offset(y = (-4).dp),
                )
            }
        }
    }
}
