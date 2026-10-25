package com.rigen.volumeui

import androidx.compose.runtime.rememberCoroutineScope
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Rules live in MemoryEngine.kt.

private val symbolColors = listOf(
    Color(0xFF64D8FF), Color(0xFF9B8CFF), Color(0xFFFFB45E),
    Color(0xFF5DE2B1), Color(0xFFFF7397), Color(0xFFFFD66B),
)

private class MemGrid(val card: Float, val gap: Float, val ox: Float, val oy: Float)

private fun memGrid(w: Float, h: Float, level: MemoryLevel): MemGrid {
    val gap = min(w, h) * 0.025f
    val card = min((w - gap * (level.cols + 1)) / level.cols, (h - gap * (level.rows + 1)) / level.rows)
    val bw = card * level.cols + gap * (level.cols - 1)
    val bh = card * level.rows + gap * (level.rows - 1)
    return MemGrid(card, gap, (w - bw) / 2f, (h - bh) / 2f)
}

private fun formatTime(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@Composable
internal fun MemoryGamePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("kanade_memory", Context.MODE_PRIVATE) }

    var levelIdx by remember { mutableIntStateOf(prefs.getInt("level", 1).coerceIn(0, MEMORY_LEVELS.lastIndex)) }
    var picking by remember { mutableStateOf(true) }
    var engine by remember { mutableStateOf(MemoryEngine(MEMORY_LEVELS[levelIdx])) }
    var rev by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var mismatchId by remember { mutableIntStateOf(0) }
    var bestMoves by remember { mutableIntStateOf(prefs.getInt("best_$levelIdx", 0)) }

    val scope = rememberCoroutineScope()
    val nero = remember { GameNero(context, "memory", scope, 300) }
    LaunchedEffect(Unit) { nero.retry() }

    val moves = rev.let { engine.moves }
    val done = rev.let { engine.done }

    fun begin(idx: Int) {
        levelIdx = idx
        prefs.edit().putInt("level", idx).apply()
        engine = MemoryEngine(MEMORY_LEVELS[idx])
        bestMoves = prefs.getInt("best_$idx", 0)
        started = false
        seconds = 0
        picking = false
        nero.begin()
        rev++
    }

    LaunchedEffect(started, done, picking, engine) {
        while (started && !done && !picking) {
            delay(1000)
            seconds++
        }
    }
    LaunchedEffect(mismatchId) {
        if (mismatchId == 0) return@LaunchedEffect
        delay(750)
        engine.hideMismatch()
        rev++
    }

    fun tap(p: Offset, w: Float, h: Float) {
        if (picking || engine.done) return
        val g = memGrid(w, h, engine.level)
        val col = ((p.x - g.ox) / (g.card + g.gap)).toInt()
        val row = ((p.y - g.oy) / (g.card + g.gap)).toInt()
        // A tap in the gap between two cards does nothing.
        val inX = (p.x - g.ox) - col * (g.card + g.gap)
        val inY = (p.y - g.oy) - row * (g.card + g.gap)
        if (p.x < g.ox || p.y < g.oy || inX > g.card || inY > g.card) return
        if (col !in 0 until engine.level.cols || row !in 0 until engine.level.rows) return
        when (engine.flip(row * engine.level.cols + col)) {
            Flip.FIRST -> started = true
            Flip.MATCH -> {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (engine.done) {
                    if (bestMoves == 0 || engine.moves < bestMoves) {
                        bestMoves = engine.moves
                        prefs.edit().putInt("best_$levelIdx", bestMoves).apply()
                    }
                    // A level is worth its pairs, times the stars it earned.
                    nero.finish(engine.level.pairs * engine.stars() * 20)
                }
            }
            Flip.MISMATCH -> mismatchId++
            Flip.IGNORED -> return
        }
        rev++
    }

    GameFrame(
        title = stringResource(R.string.memory_title),
        onBack = onBack,
        stats = listOf(
            stringResource(R.string.memory_moves) to moves.toString(),
            stringResource(R.string.memory_time) to formatTime(seconds),
            stringResource(R.string.blocks_best) to if (bestMoves > 0) bestMoves.toString() else "-",
        ),
        hint = stringResource(R.string.memory_hint),
        onRestart = { picking = true },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(engine, picking) {
                    detectTapGestures { p -> tap(p, size.width.toFloat(), size.height.toFloat()) }
                },
        ) {
            @Suppress("UNUSED_VARIABLE") val redraw = rev
            drawMemory(engine)
        }
        if (picking) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(18.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xF21B2038),
                tonalElevation = 8.dp,
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.memory_pick), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    MEMORY_LEVELS.forEachIndexed { i, lv ->
                        Spacer(Modifier.height(10.dp))
                        val name = when (i) {
                            0 -> stringResource(R.string.memory_easy)
                            1 -> stringResource(R.string.memory_medium)
                            2 -> stringResource(R.string.memory_hard)
                            else -> stringResource(R.string.memory_expert)
                        }
                        Button(onClick = { begin(i) }, modifier = Modifier.fillMaxWidth()) { Text("$name  ${lv.cols}×${lv.rows}") }
                    }
                }
            }
        } else if (done) {
            val stars = engine.stars()
            GameOverlay(
                stringResource(R.string.memory_done),
                listOf(
                    "★".repeat(stars) + "☆".repeat(3 - stars),
                    stringResource(R.string.memory_moves) + ": " + engine.moves + " · " + formatTime(seconds),
                ) + listOfNotNull(nero.line()),
                stringResource(R.string.blocks_play_again),
                { begin(levelIdx) },
                stringResource(R.string.memory_change),
                { picking = true },
            )
        }
    }
}

private fun DrawScope.drawMemory(engine: MemoryEngine) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF11172B), Color(0xFF171D36), Color(0xFF101522))), size = size)
    val lv = engine.level
    val g = memGrid(size.width, size.height, lv)
    for (i in engine.cards.indices) {
        val x = g.ox + (i % lv.cols) * (g.card + g.gap)
        val y = g.oy + (i / lv.cols) * (g.card + g.gap)
        val r = CornerRadius(g.card * 0.16f)
        val st = engine.state[i]
        if (st == CARD_HIDDEN) {
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF6A5CFF), Color(0xFF4A3FC4)), Offset(x, y), Offset(x + g.card, y + g.card)),
                Offset(x, y), Size(g.card, g.card), r,
            )
            drawCircle(Color.White.copy(alpha = 0.18f), g.card * 0.12f, Offset(x + g.card / 2f, y + g.card / 2f))
        } else {
            val matched = st == CARD_MATCHED
            drawRoundRect(
                if (matched) Color(0xFF2C3A5E) else Color(0xFFF1F3FF),
                Offset(x, y), Size(g.card, g.card), r,
            )
            val id = engine.cards[i]
            val color = symbolColors[id % symbolColors.size]
            drawSymbol(id % 8, Offset(x + g.card / 2f, y + g.card / 2f), g.card * 0.30f, if (matched) color.copy(alpha = 0.85f) else color)
            if (matched) drawRoundRect(color.copy(alpha = 0.7f), Offset(x, y), Size(g.card, g.card), r, style = Stroke(width = 2.5f))
        }
    }
}

private fun polygon(c: Offset, r: Float, n: Int, start: Double, inner: Float = 1f): Path = Path().apply {
    val pts = if (inner == 1f) n else n * 2
    for (k in 0 until pts) {
        val rr = if (k % 2 == 1 && inner != 1f) r * inner else r
        val a = start + k * 2.0 * PI / pts
        val px = c.x + (rr * cos(a)).toFloat()
        val py = c.y + (rr * sin(a)).toFloat()
        if (k == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

private fun DrawScope.drawSymbol(shape: Int, c: Offset, r: Float, color: Color) {
    when (shape) {
        0 -> drawCircle(color, r, c)
        1 -> drawRoundRect(color, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), CornerRadius(r * 0.25f))
        2 -> drawPath(polygon(Offset(c.x, c.y + r * 0.1f), r * 1.15f, 3, -PI / 2), color)
        3 -> drawPath(polygon(c, r * 1.2f, 4, -PI / 2), color)
        4 -> drawPath(polygon(c, r * 1.2f, 5, -PI / 2, 0.45f), color)
        5 -> {
            val t = r * 0.55f
            drawRoundRect(color, Offset(c.x - r, c.y - t / 2f), Size(r * 2f, t), CornerRadius(t / 3f))
            drawRoundRect(color, Offset(c.x - t / 2f, c.y - r), Size(t, r * 2f), CornerRadius(t / 3f))
        }
        6 -> drawPath(polygon(c, r * 1.1f, 6, 0.0), color)
        else -> drawCircle(color, r * 0.85f, c, style = Stroke(width = r * 0.4f))
    }
}
