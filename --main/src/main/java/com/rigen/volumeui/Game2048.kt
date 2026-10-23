package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.fillMaxSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// Rules live in Game2048Engine.kt. This file draws the board and turns swipes into moves.

private const val ANIM_NANOS = 220_000_000f
private const val SLIDE_PART = 0.55f

private val tileColors = listOf(
    Color(0xFF3A4570), // empty slot
    Color(0xFFF5F0E6), Color(0xFFEDE0C8), Color(0xFFF2B179), Color(0xFFF59563),
    Color(0xFFF67C5F), Color(0xFFF65E3B), Color(0xFFEDCF72), Color(0xFFEDCC61),
    Color(0xFFEDC850), Color(0xFFEDC53F), Color(0xFFEDC22E),
)

private fun tileColor(exp: Int): Color = if (exp < tileColors.size) tileColors[exp] else Color(0xFF3C3A32)
private fun tileTextColor(exp: Int): Color = if (exp <= 2) Color(0xFF6B5E4B) else Color.White

@Composable
internal fun Game2048Page(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("kanade_2048", Context.MODE_PRIVATE) }
    val engine = remember { Game2048Engine().also { it.decode(prefs.getString("state", null)) } }
    var rev by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(maxOf(prefs.getInt("best", 0), engine.score)) }
    var last by remember { mutableStateOf<G2048Result?>(null) }
    var animId by remember { mutableIntStateOf(0) }
    var animT by remember { mutableFloatStateOf(1f) }
    var winAck by remember { mutableStateOf(engine.won) }
    var showWin by remember { mutableStateOf(false) }

    val score = rev.let { engine.score }
    val over = rev.let { engine.over }

    fun save() {
        prefs.edit().putString("state", engine.encode()).putInt("best", best).apply()
    }

    fun doMove(dir: Int) {
        if (engine.over || showWin) return
        val res = engine.move(dir)
        if (!res.moved) return
        last = res
        animId++
        if (engine.score > best) best = engine.score
        if (res.merged.isNotEmpty()) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (engine.won && !winAck) showWin = true
        rev++
        save()
    }

    fun restart() {
        engine.reset()
        last = null
        winAck = false
        showWin = false
        animT = 1f
        rev++
        save()
    }

    LaunchedEffect(animId) {
        if (animId == 0) return@LaunchedEffect
        animT = 0f
        val start = withFrameNanos { it }
        while (animT < 1f) {
            val now = withFrameNanos { it }
            animT = ((now - start) / ANIM_NANOS).coerceAtMost(1f)
        }
    }

    val measurer = rememberTextMeasurer()

    GameFrame(
        title = stringResource(R.string.g2048_title),
        onBack = onBack,
        stats = listOf(
            stringResource(R.string.blocks_score) to score.toString(),
            stringResource(R.string.blocks_best) to best.toString(),
        ),
        hint = stringResource(R.string.g2048_hint),
        onRestart = { restart() },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(engine) {
                    var acc = Offset.Zero
                    var fired = false
                    detectDragGestures(
                        onDragStart = {
                            acc = Offset.Zero
                            fired = false
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (!fired) {
                                acc += amount
                                val th = size.width * 0.06f
                                if (abs(acc.x) > th || abs(acc.y) > th) {
                                    fired = true
                                    val dir = if (abs(acc.x) > abs(acc.y)) {
                                        if (acc.x < 0) DIR_LEFT else DIR_RIGHT
                                    } else {
                                        if (acc.y < 0) DIR_UP else DIR_DOWN
                                    }
                                    doMove(dir)
                                }
                            }
                        },
                    )
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val redraw = rev
            draw2048(engine, last, animT, measurer)
        }
        if (over) {
            GameOverlay(
                stringResource(R.string.blocks_over),
                listOf(stringResource(R.string.blocks_final_score, score.toString())),
                stringResource(R.string.blocks_play_again),
                { restart() },
            )
        } else if (showWin) {
            GameOverlay(
                stringResource(R.string.g2048_won),
                listOf(stringResource(R.string.blocks_final_score, score.toString())),
                stringResource(R.string.g2048_continue),
                {
                    winAck = true
                    showWin = false
                    save()
                },
                stringResource(R.string.blocks_restart),
                { restart() },
            )
        }
    }
}

private fun DrawScope.draw2048(engine: Game2048Engine, last: G2048Result?, t: Float, measurer: TextMeasurer) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF11172B), Color(0xFF171D36), Color(0xFF101522))), size = size)
    val s = min(size.width, size.height) * 0.94f
    val ox = (size.width - s) / 2f
    val oy = (size.height - s) / 2f
    val gap = s * 0.025f
    val tile = (s - gap * (G2048_N + 1)) / G2048_N
    drawRoundRect(Color(0xFF222A4A), Offset(ox, oy), Size(s, s), CornerRadius(tile * 0.18f))

    fun pos(i: Int): Offset = Offset(
        ox + gap + (i % G2048_N) * (tile + gap),
        oy + gap + (i / G2048_N) * (tile + gap),
    )

    for (i in 0 until G2048_N * G2048_N) {
        drawRoundRect(tileColors[0], pos(i), Size(tile, tile), CornerRadius(tile * 0.14f))
    }

    fun tileAt(topLeft: Offset, exp: Int, scale: Float) {
        val sz = tile * scale
        val tl = Offset(topLeft.x + (tile - sz) / 2f, topLeft.y + (tile - sz) / 2f)
        drawRoundRect(tileColor(exp), tl, Size(sz, sz), CornerRadius(sz * 0.14f))
        val label = (1L shl exp).toString()
        val frac = when (label.length) { 1, 2 -> 0.44f; 3 -> 0.36f; 4 -> 0.29f; else -> 0.23f }
        val layout = measurer.measure(label, TextStyle(fontSize = (sz * frac).toSp(), fontWeight = FontWeight.Bold))
        drawText(
            layout,
            color = tileTextColor(exp),
            topLeft = Offset(tl.x + (sz - layout.size.width) / 2f, tl.y + (sz - layout.size.height) / 2f),
        )
    }

    val sliding = last != null && t < SLIDE_PART
    if (sliding && last != null) {
        val p = t / SLIDE_PART
        last.moves.forEach { m ->
            val a = pos(m.from)
            val b = pos(m.to)
            tileAt(Offset(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p), m.exp, 1f)
        }
        return
    }
    val pop = if (last != null && t < 1f) ((t - SLIDE_PART) / (1f - SLIDE_PART)).coerceIn(0f, 1f) else 1f
    val spawned = last?.spawned ?: -1
    val merged = last?.merged ?: emptySet()
    for (i in 0 until G2048_N * G2048_N) {
        val exp = engine.cells[i]
        if (exp == 0) continue
        val scale = when {
            pop >= 1f -> 1f
            i == spawned -> 0.4f + 0.6f * pop
            i in merged -> 1f + 0.16f * sin(PI.toFloat() * pop)
            else -> 1f
        }
        tileAt(pos(i), exp, scale)
    }
}
