package com.rigen.volumeui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * How the brain feels, from today's count and the daily limit:
 * 0 = fine, 1 = tired (half the limit), 2 = cracking (the limit), 3 = fried (double the limit).
 */
fun reelsLevel(total: Int, limit: Int): Int {
    val l = limit.coerceAtLeast(10)
    return when {
        total < l / 2 -> 0
        total < l -> 1
        total < l * 2 -> 2
        else -> 3
    }
}

fun reelsLevelColor(level: Int): Color = when (level) {
    0 -> Color(0xFF34A853)
    1 -> Color(0xFFF29900)
    2 -> Color(0xFFE5484D)
    else -> Color(0xFFB3261E)
}

/** A little cartoon brain whose face and color get worse with [level] (see [reelsLevel]). */
@Composable
fun ReelsBrainFace(level: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = when (level) {
            0 -> Color(0xFFF4B5C6)
            1 -> Color(0xFFDDB9C0)
            2 -> Color(0xFFBBA9AD)
            else -> Color(0xFF857173)
        }
        val ink = Color(0xFF2A1A1D)
        val fold = Color(0x33000000)
        val stroke = w * 0.03f

        val cx = w / 2f
        val cy = h * 0.5f

        // The body: a bumpy cloud made of circles.
        val r = w * 0.17f
        val bumps = listOf(
            Offset(cx - w * 0.24f, cy - h * 0.22f),
            Offset(cx, cy - h * 0.29f),
            Offset(cx + w * 0.24f, cy - h * 0.22f),
            Offset(cx - w * 0.31f, cy),
            Offset(cx + w * 0.31f, cy),
            Offset(cx - w * 0.22f, cy + h * 0.2f),
            Offset(cx + w * 0.22f, cy + h * 0.2f),
        )
        bumps.forEach { drawCircle(body, r, it) }
        drawCircle(body, w * 0.34f, Offset(cx, cy))

        // Folds of the brain.
        drawLine(fold, Offset(cx, cy - h * 0.38f), Offset(cx, cy - h * 0.24f), stroke, StrokeCap.Round)
        drawArc(
            color = fold, startAngle = 200f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(cx - w * 0.36f, cy - h * 0.32f), size = Size(w * 0.26f, h * 0.2f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = fold, startAngle = 230f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(cx + w * 0.1f, cy - h * 0.32f), size = Size(w * 0.26f, h * 0.2f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )

        // Eyes. A heavier eyelid the worse the brain feels; a fried brain gets crossed-out eyes.
        val eyeR = w * 0.085f
        val eyeY = cy - h * 0.04f
        val eyeXs = listOf(cx - w * 0.14f, cx + w * 0.14f)
        if (level >= 3) {
            eyeXs.forEach { ex ->
                val d = eyeR * 0.8f
                drawLine(ink, Offset(ex - d, eyeY - d), Offset(ex + d, eyeY + d), stroke, StrokeCap.Round)
                drawLine(ink, Offset(ex - d, eyeY + d), Offset(ex + d, eyeY - d), stroke, StrokeCap.Round)
            }
        } else {
            val lid = when (level) {
                0 -> 0f
                1 -> 0.35f
                else -> 0.55f
            }
            eyeXs.forEach { ex ->
                drawCircle(Color.White, eyeR, Offset(ex, eyeY))
                drawCircle(ink, eyeR * 0.5f, Offset(ex, eyeY + eyeR * 0.15f))
                if (lid > 0f) {
                    drawRect(body, Offset(ex - eyeR, eyeY - eyeR), Size(eyeR * 2f, eyeR * 2f * lid))
                    drawLine(
                        ink, Offset(ex - eyeR, eyeY - eyeR + eyeR * 2f * lid),
                        Offset(ex + eyeR, eyeY - eyeR + eyeR * 2f * lid), stroke * 0.7f, StrokeCap.Round,
                    )
                }
            }
            if (level >= 1) {
                // Dark circles under the eyes.
                eyeXs.forEach { ex ->
                    drawArc(
                        color = Color(0x66000000), startAngle = 20f, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(ex - eyeR, eyeY - eyeR * 0.1f), size = Size(eyeR * 2f, eyeR * 2f),
                        style = Stroke(stroke * 0.8f, cap = StrokeCap.Round),
                    )
                }
            }
        }

        // Mouth.
        val my = cy + h * 0.17f
        when (level) {
            0 -> drawArc(
                color = ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - w * 0.1f, my - h * 0.06f), size = Size(w * 0.2f, h * 0.12f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            1 -> drawLine(ink, Offset(cx - w * 0.07f, my), Offset(cx + w * 0.07f, my), stroke, StrokeCap.Round)
            2 -> drawArc(
                color = ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - w * 0.1f, my - h * 0.01f), size = Size(w * 0.2f, h * 0.12f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            else -> {
                val wavy = Path().apply {
                    moveTo(cx - w * 0.1f, my)
                    lineTo(cx - w * 0.05f, my + h * 0.025f)
                    lineTo(cx, my - h * 0.025f)
                    lineTo(cx + w * 0.05f, my + h * 0.025f)
                    lineTo(cx + w * 0.1f, my)
                }
                drawPath(wavy, ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        // Cracks once the limit is passed.
        if (level >= 2) {
            val crack = Color(0xFF8E1B1B)
            val p = Path().apply {
                moveTo(cx - w * 0.05f, cy - h * 0.42f)
                lineTo(cx + w * 0.03f, cy - h * 0.32f)
                lineTo(cx - w * 0.04f, cy - h * 0.24f)
                lineTo(cx + w * 0.04f, cy - h * 0.16f)
            }
            drawPath(p, crack, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (level >= 3) {
                val p2 = Path().apply {
                    moveTo(cx + w * 0.3f, cy - h * 0.14f)
                    lineTo(cx + w * 0.22f, cy - h * 0.06f)
                    lineTo(cx + w * 0.27f, cy + h * 0.02f)
                }
                drawPath(p2, crack, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
