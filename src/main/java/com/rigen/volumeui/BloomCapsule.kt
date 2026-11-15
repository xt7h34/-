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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * The capsule Kanade Bloom shows under a pop-up notification. It is as wide as the notification, sits just under
 * it, and leaves when the notification leaves. It waits until the notification has settled on screen before it
 * appears. If Android does not let the service see that window, it is placed by a rule and leaves after five
 * seconds.
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
    private var stableTicks = 0
    private var lastKey = ""
    private var cachedWindowKey = ""
    private var cachedCard: Rect? = null
    private var reported = false
    private var lastWindows: List<AccessibilityWindowInfo> = emptyList()
    private var windowsProblem = ""
    private var ruleDelay = 1200L

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
        stableTicks = 0
        lastKey = ""
        cachedWindowKey = ""
        cachedCard = null
        reported = false
        ruleDelay = BloomPrefs.ruleDelayMs(service).toLong()
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
            // The window can appear a moment before the notification slides in, so wait until it stops moving.
            val key = rect.left.toString() + "," + rect.bottom + "," + rect.right
            stableTicks = if (key == lastKey) stableTicks + 1 else 0
            lastKey = key
            if (root != null || (stableTicks >= STABLE_TICKS && elapsed >= SHOW_AFTER_MS)) {
                place(rect.bottom + dp(8), rect.left, rect.width())
            }
        } else if (!followed) {
            if (elapsed >= ruleDelay && !usingRule) {
                usingRule = true
                if (!reported) {
                    reported = true
                    writeReport(lastWindows, null, null, null)
                }
                val screenW = service.resources.displayMetrics.widthPixels
                place(ruleTop(), dp(20), screenW - dp(40))
            }
        } else {
            lostTicks++
            if (lostTicks >= 3) {
                // It was there and now it is gone: it has left, so the capsule leaves too.
                dismiss()
                return
            }
        }
        if (usingRule && elapsed >= ruleDelay + RULE_LIFETIME_MS) {
            dismiss()
            return
        }
        if (elapsed > MAX_LIFETIME_MS) {
            dismiss()
            return
        }
        main.postDelayed(tickRunnable, TICK_MS)
    }

    /**
     * The pop-up notification's card: its window is found by where it is and how big, then the card inside it is
     * measured, because on some phones the window is taller than the card and leaves a gap.
     */
    private fun findHeadsUp(): Rect? {
        val windows = try {
            service.windows.also { windowsProblem = "" }
        } catch (e: Exception) {
            windowsProblem = e.javaClass.simpleName
            lastWindows = emptyList()
            return null
        }
        lastWindows = windows
        val screenW = service.resources.displayMetrics.widthPixels
        val r = Rect()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(r)
            val h = r.height()
            if (r.top <= dp(140) && h in dp(56)..dp(280) && r.width() >= screenW * 0.6f) {
                val window = Rect(r)
                val card = cardBounds(w, window, screenW)
                if (!reported) {
                    reported = true
                    writeReport(windows, w, window, card)
                }
                return card ?: Rect(window.left + dp(12), window.top, window.right - dp(12), window.bottom)
            }
        }
        return null
    }

    /**
     * The widest box inside the window that is not the window itself, found by size only (no text is read). That is
     * the card. Kept until the window changes, so the service does not keep asking another app about its views.
     */
    private fun cardBounds(w: AccessibilityWindowInfo, window: Rect, screenW: Int): Rect? {
        val key = window.left.toString() + "," + window.top + "," + window.right + "," + window.bottom
        if (key == cachedWindowKey) return cachedCard
        var best: Rect? = null
        try {
            val rootNode = w.root
            if (rootNode != null) {
                var visited = 0
                val box = Rect()
                val tol = dp(4)
                fun walk(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 6) return
                    for (i in 0 until node.childCount) {
                        if (visited++ > 120) return
                        val child = node.getChild(i) ?: continue
                        if (child.isVisibleToUser) {
                            child.getBoundsInScreen(box)
                            val fillsWindow = box.left <= window.left + tol && box.right >= window.right - tol &&
                                box.top <= window.top + tol && box.bottom >= window.bottom - tol
                            if (!fillsWindow && box.width() >= window.width() * 0.8f && box.height() >= dp(40) &&
                                (best == null || box.bottom > best!!.bottom)
                            ) {
                                best = Rect(box)
                            }
                        }
                        walk(child, depth + 1)
                        release(child)
                    }
                }
                walk(rootNode, 0)
                release(rootNode)
            }
        } catch (e: Exception) {
            best = null
        }
        val card = best
        val result = if (card != null && card.bottom > window.top + dp(40) && card.bottom <= window.bottom && card.width() <= screenW) card else null
        cachedWindowKey = key
        cachedCard = result
        return result
    }

    /**
     * What Bloom saw, as sizes only (no text), so the placement can be fixed from real numbers. Written when it finds the
     * notification's window and also when it does not, and shown in Bloom's settings.
     */
    private fun writeReport(windows: List<AccessibilityWindowInfo>, chosen: AccessibilityWindowInfo?, window: Rect?, card: Rect?) {
        try {
            val dm = service.resources.displayMetrics
            val version = try {
                service.packageManager.getPackageInfo(service.packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
            val sb = StringBuilder()
            sb.append("Kanade ").append(version).append("\n")
            sb.append("screen ").append(dm.widthPixels).append("x").append(dm.heightPixels).append(" density ").append(dm.density).append("\n")
            sb.append("windows seen: ").append(windows.size)
            if (windowsProblem.isNotEmpty()) sb.append(" (error: ").append(windowsProblem).append(")")
            sb.append("\n")
            val r = Rect()
            windows.forEachIndexed { i, w ->
                w.getBoundsInScreen(r)
                sb.append("window ").append(i).append(": type=").append(w.type).append(" layer=").append(w.layer)
                    .append(" bounds=").append(r.toShortString()).append("\n")
            }
            if (window != null) {
                sb.append("notification window: ").append(window.toShortString()).append("\n")
                sb.append("card found: ").append(card?.toShortString() ?: "none").append("\n")
            } else {
                sb.append("notification window: NOT FOUND, so the capsule was placed by the rule\n")
            }
            // Boxes inside the notification's window, or inside every system window near the top if it was not found.
            val targets = if (chosen != null) {
                listOf(chosen)
            } else {
                windows.filter {
                    it.getBoundsInScreen(r)
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM && r.top <= dp(300) && r.height() <= dp(500)
                }
            }
            for (target in targets) {
                target.getBoundsInScreen(r)
                sb.append("boxes inside window ").append(r.toShortString()).append(":\n")
                val rootNode = target.root
                if (rootNode == null) {
                    sb.append("  (the window shows no views)\n")
                    continue
                }
                var count = 0
                val box = Rect()
                fun dump(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 6) return
                    for (i in 0 until node.childCount) {
                        if (count++ >= 40) return
                        val child = node.getChild(i) ?: continue
                        child.getBoundsInScreen(box)
                        sb.append("  ").append("-".repeat(depth)).append(" ")
                            .append(child.className?.toString()?.substringAfterLast('.') ?: "?").append(" ")
                            .append(box.toShortString())
                            .append(if (child.isVisibleToUser) " visible" else " hidden")
                            .append(if (child.isClickable) " clickable" else "")
                            .append("\n")
                        dump(child, depth + 1)
                        release(child)
                    }
                }
                dump(rootNode, 0)
                release(rootNode)
                if (count == 0) sb.append("  (no boxes)\n")
            }
            BloomDebug.set(service, sb.toString())
        } catch (e: Exception) {
            BloomDebug.set(service, "Could not write the report: " + e.javaClass.simpleName)
        }
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
    private fun ruleTop(): Int = dp(BloomPrefs.ruleTopDp(service))

    // ───────────── The window ─────────────

    private fun place(top: Int, left: Int, width: Int) {
        val lp = params
        if (root == null || lp == null) {
            createWindow(top, left, width)
            return
        }
        if (lp.y != top || lp.x != left || lp.width != width) {
            lp.y = top
            lp.x = left
            lp.width = width
            try {
                wm.updateViewLayout(root, lp)
            } catch (e: Exception) {
                // The window is gone.
            }
        }
    }

    private fun createWindow(top: Int, left: Int, width: Int) {
        try {
            val lp = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = left
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
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            shown = false
            wm.addView(container, lp)
            root = container
            params = lp
            BloomDebug.append(service, "capsule placed: top=" + top + " left=" + left + " width=" + width + (if (usingRule) " (by the rule)" else " (following the window)"))
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
        const val STABLE_TICKS = 2
        const val SHOW_AFTER_MS = 450L
        const val RULE_LIFETIME_MS = 4500L
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

/**
 * The first thing Bloom found is the main button, with its words and as wide as it can be. Anything else it found
 * is a round button with just its picture, and the last round button is "remind me in an hour".
 */
@Composable
private fun BloomCapsuleContent(
    offer: BloomOffer,
    visible: Boolean,
    label: (BloomAction) -> String,
    onAction: (BloomAction) -> Unit,
    onLater: () -> Unit,
) {
    val t by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "bloom_in")
    val actions = offer.actions.take(3)
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = t
                translationY = (1f - t) * -24.dp.toPx()
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xE61C1C1E))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val first = actions.first()
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x33FFFFFF))
                    .clickable { onAction(first) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(bloomEmoji(first.kind), fontSize = 16.sp)
                Text(
                    label(first),
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            actions.drop(1).forEach { a ->
                RoundButton(bloomEmoji(a.kind)) { onAction(a) }
            }
            RoundButton("\u23F0") { onLater() }
        }
    }
}

@Composable
private fun RoundButton(emoji: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 18.sp)
    }
}
