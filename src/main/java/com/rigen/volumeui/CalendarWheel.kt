package com.rigen.volumeui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val STEP_DEG = 22f

/**
 * Days laid out along an arc at the bottom, turned by a horizontal drag. The day at the top is the one picked;
 * the others tilt away from it and fade. A pink needle points from the centre to the picked day.
 */
@Composable
internal fun ArcDateWheel(selected: LocalDate, locale: Locale, onSelect: (LocalDate) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    var anchor by remember { mutableStateOf(selected) }
    var pos by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    // The day was changed from somewhere else (Today, the month arrows): start over from it.
    LaunchedEffect(selected) {
        if (selected != anchor.plusDays(pos.roundToLong())) {
            anchor = selected
            pos = 0f
        }
    }

    val numbers = remember(locale) { NumberFormat.getInstance(locale).apply { isGroupingUsed = false } }
    val numberPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val smallPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val monthPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }

    val ringColor = scheme.surfaceContainer
    val innerColor = scheme.surfaceContainerHigh
    val onSurface = scheme.onSurface
    val muted = scheme.onSurfaceVariant
    val accent = scheme.primary
    val needle = Color(0xFFFF2D6F)

    Box(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                val settle: () -> Unit = {
                    job?.cancel()
                    job = scope.launch {
                        animate(pos, pos.roundToLong().toFloat(), animationSpec = tween(180)) { v, _ -> pos = v }
                    }
                }
                detectHorizontalDragGestures(
                    onDragStart = { job?.cancel() },
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                ) { change, dx ->
                    change.consume()
                    val radius = size.width / 2f
                    pos -= dx / (radius * Math.toRadians(STEP_DEG.toDouble()).toFloat())
                    val day = anchor.plusDays(pos.roundToLong())
                    if (day != currentSelected) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnSelect(day)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val r = w / 2f
            val cx = w / 2f
            val cy = r + 24.dp.toPx()
            val band = 72.dp.toPx()
            drawCircle(ringColor, r, Offset(cx, cy))
            drawCircle(innerColor, r - band, Offset(cx, cy))

            val base = pos.roundToLong()
            val frac = pos - base
            for (k in -4..4) {
                val offset = k - frac
                val deg = -90f + offset * STEP_DEG
                if (abs(offset) * STEP_DEG > 78f) continue
                val rad = Math.toRadians(deg.toDouble())
                val date = anchor.plusDays(base + k)
                val isPicked = abs(offset) < 0.5f
                val fade = (1f - abs(offset) / 4.5f).coerceIn(0.2f, 1f)

                val nx = cx + (r - 26.dp.toPx()) * cos(rad).toFloat()
                val ny = cy + (r - 26.dp.toPx()) * sin(rad).toFloat()
                if (isPicked) drawCircle(accent.copy(alpha = 0.16f), 22.dp.toPx(), Offset(nx, ny))

                drawIntoCanvas { canvas ->
                    val c = canvas.nativeCanvas
                    c.save()
                    c.rotate(deg + 90f, nx, ny)
                    numberPaint.textSize = (if (isPicked) 26.dp else 20.dp).toPx()
                    numberPaint.color = (if (isPicked) accent else onSurface).copy(alpha = fade).toArgb()
                    c.drawText(numbers.format(date.dayOfMonth.toLong()), nx, ny + numberPaint.textSize / 3f, numberPaint)
                    c.restore()

                    val wx = cx + (r - 54.dp.toPx()) * cos(rad).toFloat()
                    val wy = cy + (r - 54.dp.toPx()) * sin(rad).toFloat()
                    c.save()
                    c.rotate(deg + 90f, wx, wy)
                    smallPaint.textSize = 10.dp.toPx()
                    smallPaint.color = muted.copy(alpha = fade * 0.9f).toArgb()
                    c.drawText(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), wx, wy + smallPaint.textSize / 3f, smallPaint)
                    c.restore()
                }
            }

            // The needle, from the centre to the picked day.
            drawLine(needle, Offset(cx, cy), Offset(cx, cy - (r - band) + 8.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(needle, 5.dp.toPx(), Offset(cx, cy))

            // The month of the picked day, in the middle.
            val shown = anchor.plusDays(base)
            drawIntoCanvas { canvas ->
                monthPaint.textSize = 17.dp.toPx()
                monthPaint.color = muted.toArgb()
                canvas.nativeCanvas.drawText(
                    shown.month.getDisplayName(TextStyle.FULL, locale),
                    cx,
                    cy - 40.dp.toPx(),
                    monthPaint,
                )
            }
        }
    }
}
