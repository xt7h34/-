package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Rules and geometry live in BlocksEngine.kt. This file only draws the game and forwards touches.

private val blockColors = listOf(
    Color(0xFF64D8FF), Color(0xFF9B8CFF), Color(0xFFFFB45E),
    Color(0xFF5DE2B1), Color(0xFFFF7397), Color(0xFFFFD66B),
)
private val floorColor = Color(0xFF2A3354)
private const val FLASH_NANOS = 420_000_000f

private fun BPoint.o() = Offset(x, y)

@Composable
internal fun BlocksGamePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("kanade_blocks", Context.MODE_PRIVATE) }
    // The game is restored from the last save, so leaving the page or rotating the phone does not lose it.
    val engine = remember { BlocksEngine().also { it.decode(prefs.getString("state", null)) } }

    var rev by remember { mutableIntStateOf(0) } // bumped after every change to the engine
    var best by remember { mutableIntStateOf(maxOf(prefs.getInt("best", 0), engine.score)) }
    var selected by remember { mutableIntStateOf(-1) }
    var dragSlot by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(BPoint(0f, 0f)) }
    var flash by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var flashId by remember { mutableIntStateOf(0) }
    var flashT by remember { mutableFloatStateOf(1f) }
    var layout by remember { mutableStateOf(BlocksLayout(1f, 1f)) }

    val score = rev.let { engine.score }
    val over = rev.let { engine.over }

    fun save() {
        prefs.edit().putString("state", engine.encode()).putInt("best", best).apply()
    }

    fun commit(slot: Int, x: Int, y: Int) {
        val res = engine.place(slot, x, y) ?: return
        if (engine.score > best) best = engine.score
        selected = -1
        if (res.cleared.isNotEmpty()) {
            flash = res.cleared
            flashId++
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        rev++
        save()
    }

    fun restart() {
        engine.reset()
        selected = -1
        dragSlot = -1
        flash = emptyMap()
        rev++
        save()
    }

    LaunchedEffect(flashId) {
        if (flashId == 0) return@LaunchedEffect
        flashT = 0f
        val start = withFrameNanos { it }
        while (flashT < 1f) {
            val now = withFrameNanos { it }
            flashT = ((now - start) / FLASH_NANOS).coerceAtMost(1f)
        }
        flash = emptyMap()
    }

    PageScaffold(stringResource(R.string.blocks_title), onBack, pinTitle = true) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreCard(stringResource(R.string.blocks_score), score.toString(), Modifier.weight(1f))
                ScoreCard(stringResource(R.string.blocks_best), best.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.blocks_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp)),
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { layout = BlocksLayout(it.width.toFloat(), it.height.toFloat()) }
                        .pointerInput(layout, engine) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (engine.over) return@awaitEachGesture
                                val lay = layout
                                val slot = lay.slotAt(down.position.x, down.position.y)
                                val onPiece = slot >= 0 && engine.slotShape[slot] >= 0
                                if (onPiece) {
                                    selected = slot
                                    dragSlot = slot
                                    dragPos = BPoint(down.position.x, down.position.y)
                                    down.consume()
                                }
                                val slop = viewConfiguration.touchSlop
                                var moved = false
                                var last = down.position
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    last = change.position
                                    if (!moved && (last - down.position).getDistance() > slop) moved = true
                                    if (onPiece && moved) {
                                        dragPos = BPoint(last.x, last.y)
                                        change.consume()
                                    }
                                } while (event.changes.any { it.pressed })

                                if (onPiece) {
                                    if (moved) {
                                        // The piece is shown above the finger so it stays visible while dragging.
                                        val shapeId = engine.slotShape[slot]
                                        val snapped = lay.snap(engine, shapeId, last.x, last.y - lay.a * DRAG_LIFT)
                                        if (snapped != null) commit(slot, snapped.first, snapped.second)
                                    }
                                    dragSlot = -1
                                } else if (!moved && selected >= 0 && engine.slotShape[selected] >= 0) {
                                    // Tap-to-place: with a piece selected, touch where its centre should go.
                                    val snapped = lay.snap(engine, engine.slotShape[selected], last.x, last.y)
                                    if (snapped != null) commit(selected, snapped.first, snapped.second)
                                }
                            }
                        },
                ) {
                    @Suppress("UNUSED_VARIABLE") val redraw = rev
                    drawGame(layout, engine, selected, dragSlot, dragPos, flash, flashT)
                }
                if (over) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(18.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = Color(0xF21B2038),
                        tonalElevation = 8.dp,
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.blocks_over), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.blocks_final_score, score.toString()), color = Color(0xFFBFC8FF), fontSize = 16.sp)
                            if (score > 0 && score >= best) {
                                Spacer(Modifier.height(4.dp))
                                Text(stringResource(R.string.blocks_new_best), color = Color(0xFFFFD66B), fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { restart() }) { Text(stringResource(R.string.blocks_play_again)) }
                        }
                    }
                }
            }
            TextButton(onClick = { restart() }) { Text(stringResource(R.string.blocks_restart)) }
        }
    }
}

/** How far above the finger a dragged piece floats, in tile half-widths. */
private const val DRAG_LIFT = 2.4f

@Composable
private fun ScoreCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun DrawScope.drawGame(
    lay: BlocksLayout,
    engine: BlocksEngine,
    selected: Int,
    dragSlot: Int,
    dragPos: BPoint,
    flash: Map<Int, Int>,
    flashT: Float,
) {
    val a = lay.a
    val b = lay.b
    val d = lay.depth
    drawRect(Brush.verticalGradient(listOf(Color(0xFF11172B), Color(0xFF171D36), Color(0xFF101522))), size = size)
    drawCircle(
        Color(0xFF6A5CFF).copy(alpha = 0.10f),
        radius = size.width * 0.46f,
        center = Offset(lay.cx, lay.boardTop + b * 8f),
    )

    // What the dragged piece would do if it were dropped now.
    val dragging = dragSlot >= 0 && engine.slotShape[dragSlot] >= 0
    val ghostShape = if (dragging) engine.slotShape[dragSlot] else -1
    val ghostAt: Pair<Int, Int>? =
        if (dragging) lay.snap(engine, ghostShape, dragPos.x, dragPos.y - a * DRAG_LIFT) else null
    val willClear: Set<Int> =
        if (ghostAt != null) engine.wouldClear(ghostShape, ghostAt.first, ghostAt.second) ?: emptySet() else emptySet()

    // Board slab.
    val slab = d * 0.9f
    val top = lay.project(-0.5f, -0.5f)
    val right = lay.project(BLOCKS_SIZE - 0.5f, -0.5f)
    val bottom = lay.project(BLOCKS_SIZE - 0.5f, BLOCKS_SIZE - 0.5f)
    val left = lay.project(-0.5f, BLOCKS_SIZE - 0.5f)
    drawPath(Path().apply {
        moveTo(left.x, left.y); lineTo(bottom.x, bottom.y); lineTo(bottom.x, bottom.y + slab); lineTo(left.x, left.y + slab); close()
    }, Color(0xFF1B2140))
    drawPath(Path().apply {
        moveTo(right.x, right.y); lineTo(bottom.x, bottom.y); lineTo(bottom.x, bottom.y + slab); lineTo(right.x, right.y + slab); close()
    }, Color(0xFF141933))
    drawPath(Path().apply {
        moveTo(top.x, top.y); lineTo(right.x, right.y); lineTo(bottom.x, bottom.y); lineTo(left.x, left.y); close()
    }, Color(0xFF222A4A))

    // Floor tiles first, so the sides of raised blocks are never painted over by a neighbouring floor tile.
    for (y in 0 until BLOCKS_SIZE) for (x in 0 until BLOCKS_SIZE) {
        val c = lay.project(x.toFloat(), y.toFloat())
        drawDiamond(c, a * 0.95f, b * 0.95f, floorColor)
    }
    // Rows and columns that the dragged piece would complete.
    willClear.forEach { idx ->
        val c = lay.project((idx % BLOCKS_SIZE).toFloat(), (idx / BLOCKS_SIZE).toFloat())
        drawDiamond(c, a * 0.95f, b * 0.95f, Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.75f))
    }
    // Landing spot of the dragged piece.
    if (ghostAt != null) {
        val shape = BlockShapes.all[ghostShape]
        val color = blockColors[engine.slotColor[dragSlot]]
        shape.cells.forEach { (dx, dy) ->
            val c = lay.project((ghostAt.first + dx).toFloat(), (ghostAt.second + dy).toFloat())
            drawDiamond(c, a * 0.95f, b * 0.95f, color.copy(alpha = 0.55f), Color.White.copy(alpha = 0.85f))
        }
    }
    // Placed blocks, back to front.
    for (s in 0..(BLOCKS_SIZE - 1) * 2) for (y in 0 until BLOCKS_SIZE) {
        val x = s - y
        if (x !in 0 until BLOCKS_SIZE) continue
        val v = engine.board[y * BLOCKS_SIZE + x]
        if (v >= 0) drawBlock(lay.project(x.toFloat(), y.toFloat()), a * 0.95f, b * 0.95f, d, blockColors[v % blockColors.size])
    }
    // Lines that were just cleared fade up and out.
    if (flash.isNotEmpty() && flashT < 1f) {
        val t = flashT
        flash.forEach { (idx, col) ->
            val c = lay.project((idx % BLOCKS_SIZE).toFloat(), (idx / BLOCKS_SIZE).toFloat())
            val k = 1f - t * 0.35f
            drawBlock(
                BPoint(c.x, c.y - t * a * 0.7f), a * 0.95f * k, b * 0.95f * k, d * k,
                lerp(blockColors[col % blockColors.size], Color.White, (1f - t) * 0.55f), 1f - t,
            )
        }
    }

    // The tray with the three pieces.
    val slotW = size.width / BLOCKS_SLOTS
    val slotTop = lay.trayTop - a * 0.25f
    val slotH = (lay.trayCenterY - slotTop) * 2f
    for (i in 0 until BLOCKS_SLOTS) {
        val sel = i == selected
        val x0 = slotW * i + 6f
        val rr = CornerRadius(a * 0.5f)
        drawRoundRect(
            if (sel) Color(0xFF8B80FF).copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
            topLeft = Offset(x0, slotTop), size = Size(slotW - 12f, slotH), cornerRadius = rr,
        )
        if (sel) {
            drawRoundRect(
                Color(0xFF9C92FF), topLeft = Offset(x0, slotTop), size = Size(slotW - 12f, slotH),
                cornerRadius = rr, style = Stroke(2f),
            )
        }
        val id = engine.slotShape[i]
        if (id < 0) continue
        val center = BPoint(lay.slotCenterX(i), lay.trayCenterY + d * lay.trayScale / 2f)
        val fade = if (i == dragSlot) 0.25f else 1f
        drawPiece(lay, BlockShapes.all[id], center, lay.trayScale, blockColors[engine.slotColor[i]], fade)
    }

    // The piece under the finger.
    if (dragging) {
        val id = engine.slotShape[dragSlot]
        drawPiece(
            lay, BlockShapes.all[id], BPoint(dragPos.x, dragPos.y - a * DRAG_LIFT), 1f,
            blockColors[engine.slotColor[dragSlot]], if (ghostAt != null) 0.95f else 0.7f,
        )
    }
}

/** Draws a whole piece with its bounding box centred on [center]. */
private fun DrawScope.drawPiece(lay: BlocksLayout, shape: BlockShape, center: BPoint, scale: Float, color: Color, alpha: Float) {
    shape.cells.sortedBy { it.first + it.second }.forEach { (dx, dy) ->
        drawBlock(lay.pieceCell(shape, dx, dy, center, scale), lay.a * 0.95f * scale, lay.b * 0.95f * scale, lay.depth * scale, color, alpha)
    }
}

private fun diamondPath(c: BPoint, a: Float, b: Float, lift: Float = 0f) = Path().apply {
    moveTo(c.x, c.y - b - lift)
    lineTo(c.x + a, c.y - lift)
    lineTo(c.x, c.y + b - lift)
    lineTo(c.x - a, c.y - lift)
    close()
}

private fun DrawScope.drawDiamond(c: BPoint, a: Float, b: Float, fill: Color, outline: Color? = null) {
    val p = diamondPath(c, a, b)
    drawPath(p, fill)
    drawPath(p, outline ?: Color.White.copy(alpha = 0.06f), style = Stroke(width = 1.2f))
}

/** A raised block: its floor tile is centred on [c], the top face sits [depth] above it. */
private fun DrawScope.drawBlock(c: BPoint, a: Float, b: Float, depth: Float, color: Color, alpha: Float = 1f) {
    val ty = c.y - depth
    val left = Path().apply {
        moveTo(c.x - a, ty); lineTo(c.x, ty + b); lineTo(c.x, ty + b + depth); lineTo(c.x - a, ty + depth); close()
    }
    val right = Path().apply {
        moveTo(c.x + a, ty); lineTo(c.x, ty + b); lineTo(c.x, ty + b + depth); lineTo(c.x + a, ty + depth); close()
    }
    val top = diamondPath(BPoint(c.x, ty), a, b)
    drawPath(left, lerp(color, Color.Black, 0.30f), alpha = alpha)
    drawPath(right, lerp(color, Color.Black, 0.48f), alpha = alpha)
    drawPath(
        top,
        Brush.linearGradient(
            listOf(lerp(color, Color.White, 0.20f), color),
            start = Offset(c.x - a, ty - b), end = Offset(c.x + a, ty + b),
        ),
        alpha = alpha,
    )
    drawPath(top, Color.White.copy(alpha = 0.25f * alpha), style = Stroke(width = 1.2f))
}
