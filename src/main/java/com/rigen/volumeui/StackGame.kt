package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min

// Rules and physics live in StackEngine.kt.

private const val LAYER_H = 0.30f      // layer height in tile units
private const val K_X = 0.866f         // isometric: horizontal scale of one tile unit
private const val K_Y = 0.5f           // isometric: vertical scale of one tile unit along the ground
private const val VISIBLE_LAYERS = 60

@Composable
internal fun StackGamePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("kanade_stack", Context.MODE_PRIVATE) }
    val engine = remember { StackEngine() }
    var frame by remember { mutableIntStateOf(0) }          // read only while drawing
    var camY by remember { mutableFloatStateOf(LAYER_H) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best", 0)) }
    var over by remember { mutableStateOf(false) }
    var perfectId by remember { mutableIntStateOf(0) }
    var perfectShow by remember { mutableStateOf(false) }
    var perfectCombo by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var prev = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - prev) / 1_000_000_000f).coerceAtMost(0.05f)
            prev = now
            engine.tick(dt)
            val target = engine.layers.size * LAYER_H
            camY += (target - camY) * min(1f, dt * 5f)
            frame++
        }
    }
    LaunchedEffect(perfectId) {
        if (perfectId == 0) return@LaunchedEffect
        perfectShow = true
        delay(650)
        perfectShow = false
    }

    fun drop() {
        if (over) return
        engine.drop()
        score = engine.score
        if (engine.over) {
            over = true
            if (score > best) {
                best = score
                prefs.edit().putInt("best", best).apply()
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (engine.lastPerfect) {
            perfectCombo = engine.combo
            perfectId++
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun restart() {
        engine.reset()
        score = 0
        over = false
        perfectShow = false
        camY = LAYER_H
    }

    GameFrame(
        title = stringResource(R.string.stack_title),
        onBack = onBack,
        stats = listOf(
            stringResource(R.string.blocks_score) to score.toString(),
            stringResource(R.string.blocks_best) to best.toString(),
        ),
        hint = stringResource(R.string.stack_hint),
        onRestart = { restart() },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(engine) {
                    detectTapGestures(onPress = { drop() })
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val redraw = frame
            drawStack(engine, camY)
        }
        if (perfectShow) {
            Text(
                stringResource(R.string.stack_perfect) + if (perfectCombo > 1) "  x$perfectCombo" else "",
                color = Color(0xFFFFD66B),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
            )
        }
        if (over) {
            GameOverlay(
                stringResource(R.string.blocks_over),
                listOf(stringResource(R.string.stack_height, score.toString())),
                stringResource(R.string.blocks_play_again),
                { restart() },
            )
        }
    }
}

private fun layerColor(index: Int): Color = Color.hsv(((index * 9) % 360).toFloat(), 0.55f, 0.95f)

private fun DrawScope.drawStack(engine: StackEngine, camY: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF11172B), Color(0xFF171D36), Color(0xFF101522))), size = size)
    val u = size.width * 0.21f
    val originX = size.width / 2f
    val anchorY = size.height * 0.64f

    // World (x, z across the ground, y up) to screen.
    fun px(x: Float, z: Float) = originX + (x - z) * K_X * u
    fun py(x: Float, z: Float, y: Float) = anchorY + (x + z) * K_Y * u - (y - camY) * u

    fun box(cx: Float, cz: Float, sx: Float, sz: Float, yBottom: Float, color: Color, alpha: Float = 1f) {
        val x0 = cx - sx / 2f
        val x1 = cx + sx / 2f
        val z0 = cz - sz / 2f
        val z1 = cz + sz / 2f
        val yTop = yBottom + LAYER_H
        val top = Path().apply {
            moveTo(px(x0, z0), py(x0, z0, yTop)); lineTo(px(x1, z0), py(x1, z0, yTop))
            lineTo(px(x1, z1), py(x1, z1, yTop)); lineTo(px(x0, z1), py(x0, z1, yTop)); close()
        }
        val left = Path().apply { // plane z = z1
            moveTo(px(x0, z1), py(x0, z1, yTop)); lineTo(px(x1, z1), py(x1, z1, yTop))
            lineTo(px(x1, z1), py(x1, z1, yBottom)); lineTo(px(x0, z1), py(x0, z1, yBottom)); close()
        }
        val right = Path().apply { // plane x = x1
            moveTo(px(x1, z0), py(x1, z0, yTop)); lineTo(px(x1, z1), py(x1, z1, yTop))
            lineTo(px(x1, z1), py(x1, z1, yBottom)); lineTo(px(x1, z0), py(x1, z0, yBottom)); close()
        }
        drawPath(left, androidx.compose.ui.graphics.lerp(color, Color.Black, 0.30f), alpha = alpha)
        drawPath(right, androidx.compose.ui.graphics.lerp(color, Color.Black, 0.48f), alpha = alpha)
        drawPath(top, color, alpha = alpha)
        drawPath(top, Color.White.copy(alpha = 0.22f * alpha), style = Stroke(width = 1.2f))
    }

    val from = (engine.layers.size - VISIBLE_LAYERS).coerceAtLeast(0)
    for (i in from until engine.layers.size) {
        val l = engine.layers[i]
        box(l.cx, l.cz, l.sx, l.sz, i * LAYER_H, layerColor(l.color))
    }
    engine.debris.forEach { d ->
        box(d.cx, d.cz, d.sx, d.sz, d.level * LAYER_H - d.y, layerColor(d.color), (1f - d.y / 12f).coerceIn(0.2f, 1f))
    }
    if (!engine.over) {
        val m = engine.moving()
        box(m.cx, m.cz, m.sx, m.sz, engine.layers.size * LAYER_H, layerColor(m.color))
    }
}
