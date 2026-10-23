package com.rigen.volumeui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Kanade Tycoon: everything that is drawn (the office, the phone preview, backgrounds, floating numbers).
 * No pictures are needed: all of it is painted with Canvas, so it stays small and sharp.
 */

internal object TyColors {
    val homeTop = Color(0xFF2B6FB8)
    val homeBottom = Color(0xFF142A5C)
    val buildTop = Color(0xFF5B2BA8)
    val buildBottom = Color(0xFF1A1450)
    val priceTop = Color(0xFF14183A)
    val priceBottom = Color(0xFF05060F)
    val panelTop = Color(0xFF1C2A66)
    val panelBottom = Color(0xFF3A1F66)
    val orange = Color(0xFFFFA43A)
    val green = Color(0xFF5DE2B1)
    val red = Color(0xFFFF6B6B)
    val cyan = Color(0xFF64D8FF)
}

/** A frame clock: the value changes about 60 times a second, so anything reading it animates. */
@Composable
internal fun rememberTyClock(): State<Long> {
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameNanos { now.longValue = (System.nanoTime() - start) / 1_000_000L }
        }
    }
    return now as State<Long>
}

@Composable
internal fun TyBackdrop(top: Color, bottom: Color, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom))), content = content)
}

/* ───────────── floating numbers: "+$1.2K", "+3 research" ───────────── */

/** x and y are fractions (0..1) of the screen. */
internal class TyPopup(val id: Long, val text: String, val color: Color, val born: Long, val x: Float, val y: Float)

@Composable
internal fun TyPopups(items: List<TyPopup>, nowState: State<Long>) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        items.forEach { p ->
            val age = (nowState.value - p.born).coerceAtLeast(0) / 1800f
            if (age in 0f..1f) {
                Text(
                    p.text,
                    color = p.color,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = maxWidth * p.x, y = maxHeight * p.y - (age * 70f).dp)
                        .alpha((1f - age).coerceIn(0f, 1f)),
                )
            }
        }
    }
}

/* ───────────── the isometric office ───────────── */

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private class Iso(val ox: Float, val oy: Float, val u: Float) {
    fun x(gx: Float, gy: Float) = ox + (gx - gy) * u
    fun y(gx: Float, gy: Float, gz: Float = 0f) = oy + (gx + gy) * u * 0.5f - gz * u
    fun o(gx: Float, gy: Float, gz: Float = 0f) = Offset(x(gx, gy), y(gx, gy, gz))
}

private fun DrawScope.poly(a: Offset, b: Offset, c: Offset, d: Offset, color: Color) {
    val path = Path()
    path.moveTo(a.x, a.y)
    path.lineTo(b.x, b.y)
    path.lineTo(c.x, c.y)
    path.lineTo(d.x, d.y)
    path.close()
    drawPath(path, color)
}

private fun shade(c: Color, k: Float) = Color(
    (c.red * k).coerceIn(0f, 1f), (c.green * k).coerceIn(0f, 1f), (c.blue * k).coerceIn(0f, 1f), c.alpha,
)

private fun DrawScope.box(iso: Iso, gx: Float, gy: Float, w: Float, d: Float, h: Float, base: Color, z0: Float = 0f) {
    val z1 = z0 + h
    // left face (towards +y), right face (towards +x), top
    poly(iso.o(gx, gy + d, z0), iso.o(gx + w, gy + d, z0), iso.o(gx + w, gy + d, z1), iso.o(gx, gy + d, z1), color = shade(base, 0.72f))
    poly(iso.o(gx + w, gy, z0), iso.o(gx + w, gy + d, z0), iso.o(gx + w, gy + d, z1), iso.o(gx + w, gy, z1), color = shade(base, 0.55f))
    poly(iso.o(gx, gy, z1), iso.o(gx + w, gy, z1), iso.o(gx + w, gy + d, z1), iso.o(gx, gy + d, z1), color = base)
}

private val SKINS = listOf(Color(0xFFF2C9A0), Color(0xFFD9A273), Color(0xFFB57A4F), Color(0xFFF7D9BC))
private val SHIRTS = listOf(Color(0xFF3D7BF5), Color(0xFFFF6B8B), Color(0xFF5DE2B1), Color(0xFFFFB45E), Color(0xFFA78BFA), Color(0xFF64D8FF))

private fun DrawScope.worker(iso: Iso, gx: Float, gy: Float, i: Int, t: Float, busy: Boolean) {
    val bob = if (busy) sin(t * 5f + i * 1.7f) * 0.05f else sin(t * 1.2f + i) * 0.015f
    val base = iso.o(gx, gy, 0.05f)
    val u = iso.u
    // chair
    drawOval(Color(0x33000000), Offset(base.x - u * 0.34f, base.y - u * 0.1f), Size(u * 0.68f, u * 0.3f))
    // body
    drawRoundRect(SHIRTS[i % SHIRTS.size], Offset(base.x - u * 0.2f, base.y - u * (0.78f - bob)), Size(u * 0.4f, u * 0.62f), CornerRadius(u * 0.14f))
    // head
    drawCircle(SKINS[i % SKINS.size], u * 0.17f, Offset(base.x, base.y - u * (0.92f - bob)))
    drawArc(Color(0xFF2B2230), 180f, 180f, true, Offset(base.x - u * 0.17f, base.y - u * (1.09f - bob)), Size(u * 0.34f, u * 0.3f))
}

/**
 * [lab] and [factory] are the company levels: a bigger lab means more desks with people, a bigger factory
 * means more boxes. [busy] makes the people type faster while research or production is running.
 */
@Composable
internal fun TyOffice(lab: Int, factory: Int, researching: Int, producing: Boolean, nowState: State<Long>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = nowState.value / 1000f
        val n = 6f
        val u = min(size.width / (2f * n + 1f), size.height / (n + 3.2f))
        val iso = Iso(size.width / 2f, size.height * 0.5f - (n * u * 0.5f) + u * 0.9f, u)

        // ground shadow
        drawOval(Color(0x33000000), Offset(iso.x(0f, n) - u * 0.6f, iso.y(n * 0.5f, n * 0.5f) - u * 0.4f), Size(u * (2f * n + 1.2f), u * (n + 0.9f)))

        // floor
        val floor = Color(0xFFE8E4DC)
        poly(iso.o(0f, 0f), iso.o(n, 0f), iso.o(n, n), iso.o(0f, n), color = floor)
        for (k in 0..n.toInt()) {
            drawLine(Color(0x14000000), iso.o(k.toFloat(), 0f), iso.o(k.toFloat(), n), 1.5f)
            drawLine(Color(0x14000000), iso.o(0f, k.toFloat()), iso.o(n, k.toFloat()), 1.5f)
        }
        // rug
        poly(iso.o(1.4f, 1.6f), iso.o(4.4f, 1.6f), iso.o(4.4f, 4.4f), iso.o(1.4f, 4.4f), color = Color(0x335B7FD6))

        // walls
        val wallH = 2.6f
        poly(iso.o(0f, 0f), iso.o(n, 0f), iso.o(n, 0f, wallH), iso.o(0f, 0f, wallH), color = Color(0xFF9FB4D6))
        poly(iso.o(0f, 0f), iso.o(0f, n), iso.o(0f, n, wallH), iso.o(0f, 0f, wallH), color = Color(0xFF7F98C4))
        // windows on the back-right wall, with a light that slowly moves
        for (k in 0 until 3) {
            val a = 0.6f + k * 1.8f
            poly(iso.o(a, 0f, 0.8f), iso.o(a + 1.2f, 0f, 0.8f), iso.o(a + 1.2f, 0f, 2.2f), iso.o(a, 0f, 2.2f), color = Color(0xFFBFE6FF))
            poly(iso.o(a, 0f, 0.8f), iso.o(a + 0.4f, 0f, 0.8f), iso.o(a + 0.4f, 0f, 2.2f), iso.o(a, 0f, 2.2f), color = Color(0x55FFFFFF))
        }
        // screen on the left wall
        poly(iso.o(0f, 1.2f, 1.0f), iso.o(0f, 3.0f, 1.0f), iso.o(0f, 3.0f, 2.0f), iso.o(0f, 1.2f, 2.0f), color = Color(0xFF1B2540))
        val bars = 5
        for (b in 0 until bars) {
            val h = 0.15f + 0.6f * ((sin(t * 1.3f + b * 1.1f) + 1f) / 2f)
            poly(
                iso.o(0f, 1.3f + b * 0.33f, 1.05f), iso.o(0f, 1.3f + b * 0.33f + 0.2f, 1.05f),
                iso.o(0f, 1.3f + b * 0.33f + 0.2f, 1.05f + h * 0.85f), iso.o(0f, 1.3f + b * 0.33f, 1.05f + h * 0.85f),
                color = if (b % 2 == 0) TyColors.green else TyColors.cyan,
            )
        }
        // wall edge
        drawLine(Color(0x55FFFFFF), iso.o(0f, 0f), iso.o(0f, 0f, wallH), 3f)

        // desks with people: the lab level decides how many
        val seats = listOf(
            1.2f to 0.9f, 3.2f to 0.9f, 5.0f to 0.9f,
            0.9f to 2.6f, 0.9f to 4.4f, 2.3f to 3.7f,
            3.9f to 3.4f, 4.4f to 4.9f,
        )
        val count = (2 + lab).coerceIn(2, seats.size)
        val busyAll = researching > 0 || producing
        seats.take(count).forEachIndexed { i, (gx, gy) ->
            box(iso, gx, gy, 1.5f, 0.8f, 0.6f, Color(0xFFB7855A))
            // monitor
            box(iso, gx + 0.55f, gy + 0.2f, 0.4f, 0.05f, 0.5f, Color(0xFF263044), 0.6f)
            val glow = 0.5f + 0.5f * sin(t * 3f + i)
            drawCircle(Color(0x3364D8FF), u * 0.34f * (0.8f + 0.2f * glow), iso.o(gx + 0.75f, gy + 0.3f, 0.95f))
            worker(iso, gx + 0.75f, gy + 1.2f, i, t, busyAll && i < max(1, researching + (if (producing) 1 else 0)))
        }

        // factory boxes near the front corner
        val crates = (factory + 1).coerceIn(1, 7)
        for (c in 0 until crates) {
            val row = c / 3
            val col = c % 3
            box(iso, 4.9f - col * 0.5f, 5.0f - row * 0.5f, 0.45f, 0.45f, 0.45f, Color(0xFFC9A26B))
        }
        if (producing) {
            // a package slides along a belt
            val p = (t * 0.5f) % 1f
            box(iso, 2.2f + p * 2.2f, 5.3f, 0.35f, 0.35f, 0.3f, Color(0xFFFFB45E), 0.05f)
            poly(iso.o(2.0f, 5.2f, 0.04f), iso.o(4.8f, 5.2f, 0.04f), iso.o(4.8f, 5.75f, 0.04f), iso.o(2.0f, 5.75f, 0.04f), color = Color(0x55333A55))
        }

        // plants
        for ((gx, gy) in listOf(0.3f to 0.3f, 5.6f to 0.3f)) {
            val p = iso.o(gx, gy, 0.5f)
            box(iso, gx - 0.15f, gy - 0.15f, 0.3f, 0.3f, 0.5f, Color(0xFFEFEFEF))
            for (k in 0 until 5) {
                val ang = k * 1.26f + sin(t * 1.2f + k) * 0.12f
                drawCircle(Color(0xFF3FA564), u * 0.2f, Offset(p.x + cos(ang) * u * 0.26f, p.y - u * 0.25f + sin(ang) * u * 0.12f))
            }
            drawCircle(Color(0xFF58C77C), u * 0.24f, Offset(p.x, p.y - u * 0.42f))
        }

        // room outline
        drawLine(Color(0x66FFFFFF), iso.o(0f, n), iso.o(n, n), 3f)
        drawLine(Color(0x66FFFFFF), iso.o(n, 0f), iso.o(n, n), 3f)
    }
}

/* ───────────── the device preview ───────────── */

/** A phone (or earbuds) that changes as the parts change: a better display glows more, more cameras show more lenses. */
@Composable
internal fun TyDevicePreview(cat: String, tiers: Map<String, Int>, hue: Float, nowState: State<Long>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = nowState.value / 1000f
        if (cat == "earbuds") {
            val s = min(size.width, size.height)
            val c = Offset(size.width / 2f, size.height / 2f)
            val body = Color.hsv(hue, 0.25f, 0.95f)
            // case
            drawRoundRect(shade(body, 0.8f), Offset(c.x - s * 0.34f, c.y + s * 0.02f), Size(s * 0.68f, s * 0.34f), CornerRadius(s * 0.14f))
            drawRoundRect(body, Offset(c.x - s * 0.34f, c.y - s * 0.02f), Size(s * 0.68f, s * 0.3f), CornerRadius(s * 0.14f))
            // two buds
            for (side in listOf(-1f, 1f)) {
                val bx = c.x + side * s * 0.17f
                val by = c.y - s * 0.2f + sin(t * 2f + side) * s * 0.01f
                drawCircle(Color.White, s * 0.1f, Offset(bx, by))
                drawRoundRect(Color.White, Offset(bx - s * 0.03f + side * s * 0.03f, by), Size(s * 0.06f, s * 0.2f), CornerRadius(s * 0.03f))
                drawCircle(Color(0xFFDDE3F2), s * 0.05f, Offset(bx, by))
            }
            val anc = tiers["anc"] ?: 1
            for (k in 0 until min(anc, 4)) {
                drawCircle(TyColors.cyan.copy(alpha = 0.18f), s * (0.18f + k * 0.07f), Offset(c.x, c.y - s * 0.12f), style = Stroke(width = 3f))
            }
            return@Canvas
        }
        val h = size.height * 0.94f
        val w = min(size.width * 0.9f, h * 0.5f)
        val x0 = (size.width - w) / 2f
        val y0 = (size.height - h) / 2f
        val body = Color.hsv(hue, 0.35f, 0.9f)
        drawRoundRect(Color(0x55000000), Offset(x0 + w * 0.05f, y0 + h * 0.03f), Size(w, h), CornerRadius(w * 0.18f))
        drawRoundRect(body, Offset(x0, y0), Size(w, h), CornerRadius(w * 0.18f))
        drawRoundRect(Color(0xFF0B0E1A), Offset(x0 + w * 0.04f, y0 + h * 0.025f), Size(w * 0.92f, h * 0.95f), CornerRadius(w * 0.15f))
        val disp = tiers["display"] ?: 1
        val sc = Offset(x0 + w * 0.07f, y0 + h * 0.055f)
        val ss = Size(w * 0.86f, h * 0.89f)
        // the screen: a moving gradient, brighter and more colourful with the display tier
        val k = (0.45f + 0.1f * disp).coerceAtMost(1f)
        val shift = (sin(t * 0.8f) + 1f) / 2f
        drawRoundRect(
            Brush.linearGradient(
                listOf(Color.hsv((hue + 200f) % 360f, 0.7f, k), Color.hsv((hue + 280f + shift * 40f) % 360f, 0.8f, k), Color.hsv((hue + 330f) % 360f, 0.8f, k)),
                Offset(sc.x, sc.y), Offset(sc.x + ss.width, sc.y + ss.height),
            ),
            sc, ss, CornerRadius(w * 0.12f),
        )
        // status bar and clock
        drawRoundRect(Color(0x66FFFFFF), Offset(sc.x + ss.width * 0.3f, sc.y + ss.height * 0.14f), Size(ss.width * 0.4f, ss.height * 0.05f), CornerRadius(8f))
        // app icons
        for (r in 0 until 3) for (c in 0 until 3) {
            drawRoundRect(
                Color(0x44FFFFFF),
                Offset(sc.x + ss.width * (0.12f + c * 0.29f), sc.y + ss.height * (0.3f + r * 0.14f)),
                Size(ss.width * 0.2f, ss.width * 0.2f), CornerRadius(10f),
            )
        }
        // camera island with a lens per camera tier (up to 4)
        val lenses = ((tiers["camera"] ?: 1) + 1).coerceIn(1, 4) / 2 + 1
        val ix = x0 + w * 0.5f
        drawRoundRect(Color(0xFF05060C), Offset(ix - w * 0.12f * lenses.coerceAtLeast(1) / 1.5f, y0 + h * 0.035f), Size(w * 0.16f * lenses / 1.5f + w * 0.08f, h * 0.035f), CornerRadius(20f))
        for (l in 0 until lenses) drawCircle(Color(0xFF1C2A66), w * 0.02f, Offset(ix - w * 0.05f + l * w * 0.07f, y0 + h * 0.052f))
        // chip glow at the bottom
        val chip = tiers["chip"] ?: 1
        val glow = 0.4f + 0.6f * ((sin(t * 2.2f) + 1f) / 2f)
        drawCircle(TyColors.cyan.copy(alpha = 0.12f * glow * (1 + chip * 0.3f)), w * 0.4f, Offset(x0 + w * 0.5f, y0 + h * 0.88f))
        // battery
        val bat = (tiers["battery"] ?: 1).coerceIn(1, 6)
        drawRoundRect(Color(0x55FFFFFF), Offset(sc.x + ss.width * 0.1f, sc.y + ss.height * 0.93f), Size(ss.width * 0.8f, 6f), CornerRadius(4f))
        drawRoundRect(TyColors.green, Offset(sc.x + ss.width * 0.1f, sc.y + ss.height * 0.93f), Size(ss.width * 0.8f * (bat / 6f), 6f), CornerRadius(4f))
    }
}

/* ───────────── small controls ───────────── */

@Composable
internal fun TyPill(text: String, modifier: Modifier = Modifier, prefix: String = "") {
    Row(
        modifier
            .background(Color(0x40FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix.isNotEmpty()) Text(prefix + " ", fontSize = 14.sp)
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun TyRoundButton(label: String, onClick: () -> Unit, selected: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(46.dp)
            .background(if (selected) TyColors.orange else Color(0x40FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 20.sp, color = Color.White) }
}

@Composable
internal fun TyArrowRow(value: String, onLeft: (() -> Unit)?, onRight: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "◀", color = Color.White, fontSize = 22.sp,
            modifier = Modifier.alpha(if (onLeft != null) 1f else 0.25f).clickable(enabled = onLeft != null) { onLeft?.invoke() }.padding(12.dp),
        )
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            "▶", color = Color.White, fontSize = 22.sp,
            modifier = Modifier.alpha(if (onRight != null) 1f else 0.25f).clickable(enabled = onRight != null) { onRight?.invoke() }.padding(12.dp),
        )
    }
}

internal fun absMax(a: Float, b: Float) = if (abs(a) > abs(b)) a else b
internal const val TY_TWO_PI = (2 * PI).toFloat()
