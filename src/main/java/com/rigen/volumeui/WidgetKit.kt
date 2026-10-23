package com.rigen.volumeui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.math.max

/*
 * Shared pieces of every home screen widget: what the person chose for its look, the colours that
 * choice gives, the pictures drawn into the widget (rings and bars), and the switch between the
 * small, normal and large designs.
 */

enum class WidgetKind { ALARM, PRAYER, REWARDS, SLEEP, HABITS, REELS, GOALS }

/** The three designs each widget has. Android 12 and newer pick one by the size the widget is given. */
enum class WSize { COMPACT, NORMAL, EXPANDED }

/** [style]: 0 is the widget's own look, 1 to 7 are the shared looks, 8 is one plain colour ([solid]). */
class WidgetLook(val style: Int, val solid: Int, val opacity: Int)

object WidgetLooks {
    private const val PREF = "kanade_widget_look"
    val DEFAULT_SOLID: Int = 0xFF1F2937.toInt()

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(c: Context, id: Int): WidgetLook {
        val p = sp(c)
        return WidgetLook(
            style = p.getInt("s$id", 0).coerceIn(0, 8),
            solid = p.getInt("c$id", DEFAULT_SOLID),
            opacity = p.getInt("o$id", 100).coerceIn(20, 100),
        )
    }

    fun save(c: Context, id: Int, look: WidgetLook) {
        sp(c).edit()
            .putInt("s$id", look.style.coerceIn(0, 8))
            .putInt("c$id", look.solid)
            .putInt("o$id", look.opacity.coerceIn(20, 100))
            .apply()
    }
}

/** Everything a design needs to colour itself. */
class WidgetPal(
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    /** The empty part of a ring or bar. */
    val track: Int,
    val bgRes: Int,
    /** A colour poured over the background drawable, or 0 for none (only the one-colour style uses it). */
    val bgTint: Int,
    /** The background's opacity, 0 to 255. */
    val bgAlpha: Int,
    val start: Int,
    val end: Int,
)

object WidgetStyle {
    fun palette(look: WidgetLook, kind: WidgetKind): WidgetPal {
        val alpha = look.opacity.coerceIn(20, 100) * 255 / 100
        if (look.style == 8) {
            val c = look.solid
            val lum = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)) / 255.0
            val light = lum > 0.6
            val primary = if (light) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
            return WidgetPal(
                primary = primary,
                secondary = if (light) 0xFF55555F.toInt() else 0xC8FFFFFF.toInt(),
                accent = if (light) 0xFFB45309.toInt() else 0xFFFFD76A.toInt(),
                track = (primary and 0x00FFFFFF) or 0x33000000,
                bgRes = R.drawable.wbg_solid,
                bgTint = c or 0xFF000000.toInt(),
                bgAlpha = alpha,
                start = c or 0xFF000000.toInt(),
                end = c or 0xFF000000.toInt(),
            )
        }
        val def = if (look.style in 1..7) WidgetPalettes.STYLES[look.style - 1] else WidgetPalettes.SIGNATURE.getValue(kind)
        return WidgetPal(
            primary = def.primary,
            secondary = def.secondary,
            accent = def.accent,
            track = (def.primary and 0x00FFFFFF) or 0x33000000,
            bgRes = def.bgRes,
            bgTint = 0,
            bgAlpha = alpha,
            start = def.start,
            end = def.end,
        )
    }

    /**
     * The card behind the widget. The colour filter is always set (to nothing when there is no tint),
     * because a launcher can keep the old one when it redraws the same layout.
     */
    fun applyBackground(rv: RemoteViews, pal: WidgetPal) {
        rv.setImageViewResource(R.id.w_bg, pal.bgRes)
        rv.setInt(R.id.w_bg, "setImageAlpha", pal.bgAlpha)
        rv.setInt(R.id.w_bg, "setColorFilter", pal.bgTint)
    }

    fun begin(c: Context, id: Int, kind: WidgetKind, layout: Int): Pair<RemoteViews, WidgetPal> {
        val pal = palette(WidgetLooks.load(c, id), kind)
        val rv = RemoteViews(c.packageName, layout)
        applyBackground(rv, pal)
        return rv to pal
    }
}

/** Pictures for the widgets, drawn with the palette's colours. */
object WidgetGfx {
    /** A ring that fills clockwise from the top. */
    fun ring(px: Int, progress: Float, accent: Int, track: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val stroke = px * 0.12f
        val rect = RectF(stroke / 2f, stroke / 2f, px - stroke / 2f, px - stroke / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.ROUND
        p.color = track
        cv.drawArc(rect, 0f, 360f, false, p)
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep > 0f) {
            p.color = accent
            cv.drawArc(rect, -90f, sweep, false, p)
        }
        return bmp
    }

    /** A rounded bar that fills from the start. */
    fun bar(w: Int, h: Int, progress: Float, accent: Int, track: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = h / 2f
        p.color = track
        cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        val fill = w * progress.coerceIn(0f, 1f)
        if (fill > 0f) {
            p.color = accent
            cv.drawRoundRect(RectF(0f, 0f, max(fill, h.toFloat()), h.toFloat()), r, r, p)
        }
        return bmp
    }

    /** One bar per value, with a dashed line at the goal. A day with nothing gets a small stub. */
    fun weekBars(values: List<Int>, goal: Int, w: Int, h: Int, accent: Int, track: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val n = max(1, values.size)
        val gap = w * 0.05f
        val barW = (w - gap * (n - 1)) / n
        val top = max(goal, values.maxOrNull() ?: 0).coerceAtLeast(1) * 1.1f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val corner = barW / 3f
        values.forEachIndexed { i, v ->
            val x = i * (barW + gap)
            if (v <= 0) {
                p.color = track
                cv.drawRoundRect(RectF(x, h - 6f, x + barW, h.toFloat()), corner, corner, p)
            } else {
                val bh = (v / top * h).coerceAtLeast(8f)
                p.color = if (v >= goal) accent else (accent and 0x00FFFFFF) or 0x99000000.toInt()
                cv.drawRoundRect(RectF(x, h - bh, x + barW, h.toFloat()), corner, corner, p)
            }
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.style = Paint.Style.STROKE
        line.strokeWidth = 3f
        line.color = track
        line.pathEffect = DashPathEffect(floatArrayOf(12f, 9f), 0f)
        val y = h - goal / top * h
        cv.drawLine(0f, y, w.toFloat(), y, line)
        return bmp
    }
}

object WidgetFrame {
    /**
     * On Android 12 and newer the widget carries all three designs and the launcher shows the one that
     * fits the size it was given: the large one from [expandedMin] up, the normal one from [normalMin]
     * up, the small one below. Older versions only get the normal design.
     */
    fun build(normalMin: SizeF, expandedMin: SizeF, make: (WSize) -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return make(WSize.NORMAL)
        return RemoteViews(
            mapOf(
                SizeF(40f, 40f) to make(WSize.COMPACT),
                normalMin to make(WSize.NORMAL),
                expandedMin to make(WSize.EXPANDED),
            ),
        )
    }
}
