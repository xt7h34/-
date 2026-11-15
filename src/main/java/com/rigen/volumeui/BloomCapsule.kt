package com.rigen.volumeui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The small capsule Kanade Bloom shows under a pop-up notification. It follows the real notification window:
 * it sits just under it and leaves when the notification leaves (swiped away, tapped or timed out). If Android
 * does not let the service see that window, it is placed by a rule instead and leaves after five seconds.
 */
class BloomCapsule(private val service: VolumeAccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var offer: BloomOffer? = null
    private var offerState by mutableStateOf<BloomOffer?>(null)
    private var shown by mutableStateOf(false)

    private var startedAt = 0L
    private var followed = false
    private var lostTicks = 0
    private var usingRule = false
    private var cachedWindowBottom = -1
    private var cachedCardBottom = -1

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
        }
    }
    private val removeRunnable = Runnable { removeWindow() }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    fun show(newOffer: BloomOffer) {
        main.removeCallbacks(tickRunnable)
        main.removeCallbacks(removeRunnable)
        offer = newOffer
        offerState = newOffer
        startedAt = System.currentTimeMillis()
        followed = false
        lostTicks = 0
        usingRule = false
        cachedWindowBottom = -1
        cachedCardBottom = -1
        main.postDelayed(tickRunnable, FIRST_TICK_MS)
    }

    /** The notification was removed (swiped, opened or cancelled). */
    fun onRemoved(key: String) {
        if (offer?.key == key) dismiss()
    }

    fun dismiss() {
        main.removeCallbacks(tickRunnable)
        offer = null
        if (root == null) return
        shown = false
        main.removeCallbacks(removeRunnable)
        main.postDelayed(removeRunnable, 260)
    }

    fun remove() {
        main.removeCallbacks(tickRunnable)
        offer = null
        removeWindow()
    }

    // ───────────── Following the notification window ─────────────

    private fun tick() {
        if (offer == null) return
        val elapsed = System.currentTimeMillis() - startedAt
        val rect = findHeadsUp()
        if (rect != null) {
            followed = true
            usingRule = false
            lostTicks = 0
            place(rect.bottom + dp(8))
        } else if (!followed) {
            if (elapsed >= RULE_AFTER_MS && !usingRule) {
                usingRule = true
                place(ruleTop())
            }
        } else {
            lostTicks++
            if (lostTicks >= 3) {
                // It was there and now it is gone: it has left, so the capsule leaves too.
                dismiss()
                return
            }
        }
        if (usingRule && elapsed >= RULE_LIFETIME_MS) {
            dismiss()
            return
        }
        if (elapsed > MAX_LIFETIME_MS) {
            dismiss()
            return
        }
        main.postDelayed(tickRunnable, TICK_MS)
    }

    /** The pop-up notification's window, found by where it is and how big: a wide, short window at the very top. */
    private fun findHeadsUp(): Rect? {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            return null
        }
        val screenW = service.resources.displayMetrics.widthPixels
        val r = Rect()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(r)
            val h = r.height()
            if (r.top <= dp(140) && h in dp(56)..dp(280) && r.width() >= screenW * 0.6f) {
                val card = cardBottom(w, r, screenW)
                return Rect(r.left, r.top, r.right, card)
            }
        }
        return null
    }

    /**
     * Some phones make the pop-up's window taller than the card you see, which leaves a gap. This looks for the
     * card itself: the widest tappable box inside the window. Only box sizes are read, never any text. The answer is
     * kept until the window changes size, so the service does not keep asking another app about its views.
     */
    private fun cardBottom(w: AccessibilityWindowInfo, window: Rect, screenW: Int): Int {
        if (window.bottom == cachedWindowBottom && cachedCardBottom > 0) return cachedCardBottom
        var best = -1
        try {
            val root = w.root
            if (root != null) {
                var visited = 0
                val box = Rect()
                fun walk(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 5) return
                    for (i in 0 until node.childCount) {
                        if (visited++ > 80) return
                        val child = node.getChild(i) ?: continue
                        if (child.isVisibleToUser && child.isClickable) {
                            child.getBoundsInScreen(box)
                            if (box.width() >= screenW * 0.6f && box.height() >= dp(40) && box.bottom > best) best = box.bottom
                        }
                        walk(child, depth + 1)
                        release(child)
                    }
                }
                walk(root, 0)
                release(root)
            }
        } catch (e: Exception) {
            best = -1
        }
        val result = if (best > window.top + dp(40) && best <= window.bottom) best else window.bottom
        cachedWindowBottom = window.bottom
        cachedCardBottom = result
        return result
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (e: Exception) {
            // Nothing to free.
        }
    }

    /** Where to put it when the window cannot be seen: under a typical pop-up. */
    private fun ruleTop(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (id > 0) service.resources.getDimensionPixelSize(id) else dp(24)
        return status + dp(92)
    }

    // ───────────── The window ─────────────

    private fun place(top: Int) {
        val lp = params
        if (root == null || lp == null) {
            createWindow(top)
            return
        }
        if (lp.y != top) {
            lp.y = top
            try {
                wm.updateViewLayout(root, lp)
            } catch (e: Exception) {
                // The window is gone.
            }
        }
    }

    private fun createWindow(top: Int) {
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
            lp.y = top
            val container = FrameLayout(service)
            container.setViewTreeLifecycleOwner(service)
            container.setViewTreeSavedStateRegistryOwner(service)
            val view = ComposeView(service).apply {
                setViewTreeLifecycleOwner(service)
                setViewTreeSavedStateRegistryOwner(service)
                setContent {
                    val current = offerState
                    if (current != null) {
                        BloomCapsuleContent(
                            offer = current,
                            visible = shown,
                            label = { bloomLabel(it) },
                            onAction = { a ->
                                BloomEngine.run(service, current, a)
                                dismiss()
                            },
                            onLater = {
                                BloomEngine.remindLater(service, current)
                                dismiss()
                            },
                        )
                    }
                }
            }
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            shown = false
            wm.addView(container, lp)
            root = container
            params = lp
            // A moment later, so it slides in instead of starting already open.
            main.postDelayed({ shown = true }, 30)
        } catch (e: Exception) {
            Diag.error(service, "Could not show Bloom", e)
            removeWindow()
        }
    }

    private fun removeWindow() {
        main.removeCallbacks(removeRunnable)
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        root = null
        params = null
        shown = false
    }

    // ───────────── Words on the buttons ─────────────

    private fun bloomLabel(a: BloomAction): String {
        val c = Prefs.localized(service)
        return when (a.kind) {
            BA_EVENT -> {
                // Short, so it fits: "Sun 15 Nov, 7:00 PM". The year only shows when it is not this year.
                val locale = c.resources.configuration.locales[0]
                val sameYear = CalendarEngine.ld(a.at).year == java.time.LocalDate.now().year
                val pattern = android.text.format.DateFormat.getBestDateTimePattern(
                    locale,
                    if (sameYear) "EEEdMMM" else "EEEdMMMy",
                )
                val format = java.text.SimpleDateFormat(pattern, locale)
                val date = format.format(java.util.Date(a.at))
                val whenText = when {
                    a.endAt >= a.at + CAL_MULTIDAY_MS -> date + " \u2013 " + format.format(java.util.Date(a.endAt))
                    a.allDay -> date
                    else -> date + ", " + android.text.format.DateFormat.getTimeFormat(service).format(java.util.Date(a.at))
                }
                c.getString(R.string.bloom_add_event, whenText)
            }
            BA_EXPENSE -> c.getString(R.string.bloom_add_expense, formatMoney(a.amount, a.value))
            BA_CODE -> c.getString(R.string.bloom_copy_code, a.value)
            BA_LINK -> c.getString(R.string.bloom_open_link)
            BA_MAP -> c.getString(R.string.bloom_open_map)
            else -> c.getString(R.string.bloom_call, a.value)
        }
    }

    private companion object {
        const val FIRST_TICK_MS = 150L
        const val TICK_MS = 120L
        const val RULE_AFTER_MS = 700L
        const val RULE_LIFETIME_MS = 5000L
        const val MAX_LIFETIME_MS = 15000L
    }
}

private fun bloomEmoji(kind: Int): String = when (kind) {
    BA_EVENT -> "\uD83D\uDCC5"
    BA_EXPENSE -> "\uD83D\uDCB8"
    BA_CODE -> "\uD83D\uDD11"
    BA_LINK -> "\uD83D\uDD17"
    BA_MAP -> "\uD83D\uDCCD"
    else -> "\uD83D\uDCDE"
}

@Composable
private fun BloomCapsuleContent(
    offer: BloomOffer,
    visible: Boolean,
    label: (BloomAction) -> String,
    onAction: (BloomAction) -> Unit,
    onLater: () -> Unit,
) {
    val t by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "bloom_in")
    Box(
        Modifier.graphicsLayer {
            alpha = t
            translationY = (1f - t) * -24.dp.toPx()
        },
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xE61C1C1E))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            offer.actions.take(2).forEach { a ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable { onAction(a) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(bloomEmoji(a.kind), fontSize = 15.sp)
                    Text(
                        label(a),
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 250.dp),
                    )
                }
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
                    .clickable { onLater() },
                contentAlignment = Alignment.Center,
            ) {
                Text("\u23F0", fontSize = 16.sp)
            }
        }
    }
}
