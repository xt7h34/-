package com.rigen.volumeui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The lock screen that covers a counted app once the daily limit is reached. It lives in the
 * accessibility service like the floating timer, so it needs no extra permission. While the lock is
 * on it covers the app, counts down, and sends the person back to the home screen after a moment.
 */
class ReelsLock(private val service: VolumeAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var compose: ComposeView? = null
    private var frontKey: String? = null

    private val autoHome = Runnable { if (root != null) goHome() }

    fun onForeground(pkg: String) {
        frontKey = ReelsStore.appOf(pkg)?.key
        refresh()
    }

    fun refresh() {
        val key = frontKey
        val should = key != null &&
            ReelsStore.apps(service).contains(key) &&
            ReelsStore.isLocked(service, System.currentTimeMillis())
        if (should) show() else remove()
    }

    private fun goHome() {
        try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Diag.error(service, "Could not go home from the reels lock", e)
        }
    }

    private fun show() {
        if (root != null) return
        try {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            val view = ComposeView(service).apply {
                setViewTreeLifecycleOwner(service)
                setViewTreeSavedStateRegistryOwner(service)
                setContent {
                    ReelsLockContent(onHome = { goHome() }, onExpired = { refresh() })
                }
            }
            val container = FrameLayout(service)
            container.setViewTreeLifecycleOwner(service)
            container.setViewTreeSavedStateRegistryOwner(service)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            wm.addView(container, lp)
            root = container
            compose = view
            main.postDelayed(autoHome, 3500)
        } catch (e: Exception) {
            Diag.error(service, "Could not show the reels lock", e)
        }
    }

    fun remove() {
        main.removeCallbacks(autoHome)
        val r = root ?: return
        val c = compose
        root = null
        compose = null
        try {
            wm.removeView(r)
            c?.disposeComposition()
        } catch (e: Exception) {
            Diag.error(service, "Could not hide the reels lock", e)
        }
    }
}

private fun reelsClock(ms: Long): String {
    val s = (ms / 1000L).toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
    } else {
        String.format(Locale.US, "%d:%02d", m, ss)
    }
}

@Composable
private fun ReelsLockContent(onHome: () -> Unit, onExpired: () -> Unit) {
    val ctx = LocalContext.current
    var left by remember {
        mutableLongStateOf(maxOf(0L, ReelsStore.lockUntil(ctx) - System.currentTimeMillis()))
    }
    LaunchedEffect(Unit) {
        while (true) {
            left = maxOf(0L, ReelsStore.lockUntil(ctx) - System.currentTimeMillis())
            if (left <= 0L) {
                onExpired()
                break
            }
            delay(1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A1519), Color(0xFF120708)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReelsBrainFace(3, Modifier.size(180.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.reels_lock_title),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.reels_lock_sub, ReelsStore.limit(ctx)),
                color = Color(0xB3FFFFFF),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                reelsClock(left),
                color = Color(0xFFFAE8BD),
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                stringResource(R.string.reels_lock_left_label),
                color = Color(0xB3FFFFFF),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x33FFFFFF))
                    .clickable(onClick = onHome)
                    .padding(horizontal = 32.dp, vertical = 14.dp),
            ) {
                Text(
                    stringResource(R.string.reels_lock_home),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
