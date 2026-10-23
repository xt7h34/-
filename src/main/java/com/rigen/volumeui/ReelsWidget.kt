package com.rigen.volumeui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews

/** The home-screen widget: the brain, today's count and how the brain feels. */
class ReelsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ReelsWidgetUpdater.updateNow(context)
    }
}

object ReelsWidgetUpdater {
    private val main = Handler(Looper.getMainLooper())
    private var lastAt = 0L
    private var pending = false

    /** Every counted video asks for an update; they are bunched so the launcher is not flooded. */
    fun requestUpdate(c: Context) {
        val app = c.applicationContext
        val wait = 4000L - (SystemClock.elapsedRealtime() - lastAt)
        if (wait <= 0L) {
            updateNow(app)
        } else if (!pending) {
            pending = true
            main.postDelayed({
                pending = false
                updateNow(app)
            }, wait)
        }
    }

    fun updateNow(c: Context) {
        lastAt = SystemClock.elapsedRealtime()
        try {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, ReelsWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { m.updateAppWidget(it, WidgetDesigns.reels(c, it)) }
        } catch (e: Throwable) {
            Diag.error(c, "reels widget", e)
        }
    }
}

/** The same cartoon brain as [ReelsBrainFace], drawn into a bitmap because widgets can't show Compose. */
object ReelsBrainBitmap {
    fun render(level: Int, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val w = px.toFloat()
        val h = px.toFloat()
        val body = when (level) {
            0 -> 0xFFF4B5C6
            1 -> 0xFFDDB9C0
            2 -> 0xFFBBA9AD
            else -> 0xFF857173
        }.toInt()
        val ink = 0xFF2A1A1D.toInt()
        val fold = 0x33000000
        val crack = 0xFF8E1B1B.toInt()
        val stroke = w * 0.03f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun disc(color: Int, r: Float, x: Float, y: Float) {
            fill.color = color
            cv.drawCircle(x, y, r, fill)
        }
        fun pen(color: Int, width: Float) {
            line.color = color
            line.strokeWidth = width
        }

        val cx = w / 2f
        val cy = h * 0.5f
        val r = w * 0.17f
        val bumps = listOf(
            cx - w * 0.24f to cy - h * 0.22f,
            cx to cy - h * 0.29f,
            cx + w * 0.24f to cy - h * 0.22f,
            cx - w * 0.31f to cy,
            cx + w * 0.31f to cy,
            cx - w * 0.22f to cy + h * 0.2f,
            cx + w * 0.22f to cy + h * 0.2f,
        )
        bumps.forEach { disc(body, r, it.first, it.second) }
        disc(body, w * 0.34f, cx, cy)

        // Folds.
        pen(fold, stroke)
        cv.drawLine(cx, cy - h * 0.38f, cx, cy - h * 0.24f, line)
        cv.drawArc(RectF(cx - w * 0.36f, cy - h * 0.32f, cx - w * 0.10f, cy - h * 0.12f), 200f, 110f, false, line)
        cv.drawArc(RectF(cx + w * 0.10f, cy - h * 0.32f, cx + w * 0.36f, cy - h * 0.12f), 230f, 110f, false, line)

        // Eyes.
        val eyeR = w * 0.085f
        val eyeY = cy - h * 0.04f
        val eyeXs = listOf(cx - w * 0.14f, cx + w * 0.14f)
        if (level >= 3) {
            pen(ink, stroke)
            for (ex in eyeXs) {
                val d = eyeR * 0.8f
                cv.drawLine(ex - d, eyeY - d, ex + d, eyeY + d, line)
                cv.drawLine(ex - d, eyeY + d, ex + d, eyeY - d, line)
            }
        } else {
            val lid = when (level) {
                0 -> 0f
                1 -> 0.35f
                else -> 0.55f
            }
            for (ex in eyeXs) {
                disc(0xFFFFFFFF.toInt(), eyeR, ex, eyeY)
                disc(ink, eyeR * 0.5f, ex, eyeY + eyeR * 0.15f)
                if (lid > 0f) {
                    fill.color = body
                    cv.drawRect(ex - eyeR, eyeY - eyeR, ex + eyeR, eyeY - eyeR + eyeR * 2f * lid, fill)
                    pen(ink, stroke * 0.7f)
                    cv.drawLine(
                        ex - eyeR, eyeY - eyeR + eyeR * 2f * lid,
                        ex + eyeR, eyeY - eyeR + eyeR * 2f * lid, line,
                    )
                }
            }
            if (level >= 1) {
                pen(0x66000000, stroke * 0.8f)
                for (ex in eyeXs) {
                    cv.drawArc(RectF(ex - eyeR, eyeY - eyeR * 0.1f, ex + eyeR, eyeY + eyeR * 1.9f), 20f, 140f, false, line)
                }
            }
        }

        // Mouth.
        val my = cy + h * 0.17f
        pen(ink, stroke)
        when (level) {
            0 -> cv.drawArc(RectF(cx - w * 0.1f, my - h * 0.06f, cx + w * 0.1f, my + h * 0.06f), 20f, 140f, false, line)
            1 -> cv.drawLine(cx - w * 0.07f, my, cx + w * 0.07f, my, line)
            2 -> cv.drawArc(RectF(cx - w * 0.1f, my - h * 0.01f, cx + w * 0.1f, my + h * 0.11f), 200f, 140f, false, line)
            else -> {
                val wavy = Path()
                wavy.moveTo(cx - w * 0.1f, my)
                wavy.lineTo(cx - w * 0.05f, my + h * 0.025f)
                wavy.lineTo(cx, my - h * 0.025f)
                wavy.lineTo(cx + w * 0.05f, my + h * 0.025f)
                wavy.lineTo(cx + w * 0.1f, my)
                cv.drawPath(wavy, line)
            }
        }

        // Cracks.
        if (level >= 2) {
            pen(crack, stroke)
            val p = Path()
            p.moveTo(cx - w * 0.05f, cy - h * 0.42f)
            p.lineTo(cx + w * 0.03f, cy - h * 0.32f)
            p.lineTo(cx - w * 0.04f, cy - h * 0.24f)
            p.lineTo(cx + w * 0.04f, cy - h * 0.16f)
            cv.drawPath(p, line)
            if (level >= 3) {
                val p2 = Path()
                p2.moveTo(cx + w * 0.3f, cy - h * 0.14f)
                p2.lineTo(cx + w * 0.22f, cy - h * 0.06f)
                p2.lineTo(cx + w * 0.27f, cy + h * 0.02f)
                cv.drawPath(p2, line)
            }
        }
        return bmp
    }
}
