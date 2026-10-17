package com.rigen.volumeui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.random.Random

private const val BLOCKS_SIZE = 8
private data class BlockShape(val cells: List<Pair<Int, Int>>, val color: Color)
private data class BlocksGeometry(val cellW: Float, val cellH: Float, val topY: Float, val centerX: Float, val trayY: Float)

private val blockShapes = listOf(
    listOf(0 to 0),
    listOf(0 to 0, 1 to 0),
    listOf(0 to 0, 1 to 0, 2 to 0),
    listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1),
    listOf(0 to 0, 0 to 1, 0 to 2),
    listOf(0 to 0, 1 to 0, 2 to 0, 1 to 1),
    listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),
    listOf(0 to 0, 0 to 1, 1 to 1, 1 to 2),
    listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0),
    listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1),
    listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1),
    listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1),
)
private val blockColors = listOf(
    Color(0xFF64D8FF), Color(0xFF9B8CFF), Color(0xFFFFB45E),
    Color(0xFF5DE2B1), Color(0xFFFF7397), Color(0xFFFFD66B),
)

@Composable
internal fun BlocksGamePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kanade_blocks", Context.MODE_PRIVATE) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best", 0)) }
    val board = remember { mutableStateListOf<Int>().apply { repeat(BLOCKS_SIZE * BLOCKS_SIZE) { add(-1) } } }
    val pieces = remember { mutableStateListOf<BlockShape>().apply { repeat(3) { add(randomBlock()) } } }
    var selected by remember { mutableIntStateOf(-1) }
    var gameOver by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var geometry by remember { mutableStateOf(BlocksGeometry(1f, 1f, 0f, 0f, 0f)) }

    fun resetGame() {
        for (i in board.indices) board[i] = -1
        pieces.clear()
        repeat(3) { pieces.add(randomBlock()) }
        score = 0
        selected = -1
        gameOver = false
    }

    fun canPlace(shape: BlockShape, x: Int, y: Int): Boolean {
        return shape.cells.all { (dx, dy) ->
            val px = x + dx
            val py = y + dy
            px in 0 until BLOCKS_SIZE && py in 0 until BLOCKS_SIZE && board[py * BLOCKS_SIZE + px] == -1
        }
    }

    fun anyMoveAvailable(): Boolean = pieces.any { shape ->
        shape.cells.isNotEmpty() && (0 until BLOCKS_SIZE).any { y ->
            (0 until BLOCKS_SIZE).any { x -> canPlace(shape, x, y) }
        }
    }

    fun place(x: Int, y: Int) {
        if (selected !in pieces.indices || gameOver) return
        val shape = pieces[selected]
        if (!canPlace(shape, x, y)) return
        shape.cells.forEach { (dx, dy) -> board[(y + dy) * BLOCKS_SIZE + x + dx] = blockColors.indexOf(shape.color).coerceAtLeast(0) }
        score += shape.cells.size * 5
        val fullRows = (0 until BLOCKS_SIZE).filter { row -> (0 until BLOCKS_SIZE).all { col -> board[row * BLOCKS_SIZE + col] >= 0 } }
        val fullCols = (0 until BLOCKS_SIZE).filter { col -> (0 until BLOCKS_SIZE).all { row -> board[row * BLOCKS_SIZE + col] >= 0 } }
        val cleared = (fullRows.size + fullCols.size)
        if (cleared > 0) {
            val clearIndices = mutableSetOf<Int>()
            fullRows.forEach { r -> (0 until BLOCKS_SIZE).forEach { c -> clearIndices.add(r * BLOCKS_SIZE + c) } }
            fullCols.forEach { c -> (0 until BLOCKS_SIZE).forEach { r -> clearIndices.add(r * BLOCKS_SIZE + c) } }
            clearIndices.forEach { board[it] = -1 }
            score += cleared * 100 + if (cleared > 1) cleared * 50 else 0
        }
        if (score > best) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
        pieces[selected] = BlockShape(emptyList(), Color.Transparent)
        selected = -1
        if (pieces.all { it.cells.isEmpty() }) {
            pieces.clear()
            repeat(3) { pieces.add(randomBlock()) }
        }
        if (!anyMoveAvailable()) gameOver = true
    }

    PageScaffold("Kanade Blocks", onBack) { _ ->
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(94.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScoreCard("النقاط", score.toString(), Modifier.weight(1f))
                ScoreCard("أفضل نتيجة", best.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("اختر قطعة ثم المس مكان وضعها", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier.fillMaxSize().onSizeChanged { measured ->
                        canvasSize = measured
                        val cw = min(measured.width.toFloat() / (BLOCKS_SIZE + 1.5f), measured.height.toFloat() / 19.5f).coerceAtLeast(18f)
                        val ch = cw * 0.48f
                        val boardHeight = BLOCKS_SIZE * ch * 2f
                        val trayHeight = cw * 4.2f
                        val top = ((measured.height - boardHeight - trayHeight) / 2f).coerceAtLeast(12f)
                        geometry = BlocksGeometry(cw, ch, top, measured.width / 2f, top + boardHeight + cw * 1.25f)
                    }.pointerInput(board.toList(), pieces.toList(), selected, gameOver, canvasSize) {
                        detectTapGestures { tap ->
                            val g = geometry
                            if (tap.y >= g.trayY) {
                                val section = size.width / 3f
                                val index = (tap.x / section).toInt().coerceIn(0, 2)
                                if (pieces[index].cells.isNotEmpty()) selected = index
                            } else {
                                val u = (tap.x - g.centerX) / (g.cellW / 2f)
                                val v = (tap.y - g.topY) / (g.cellH / 2f)
                                val gx = ((u + v) / 2f).toInt()
                                val gy = ((v - u) / 2f).toInt()
                                if (selected >= 0) place(gx, gy)
                            }
                        }
                    }
                ) {
                    val cw = min(size.width / (BLOCKS_SIZE + 1.5f), size.height / 19.5f).coerceAtLeast(18f)
                    val ch = cw * 0.48f
                    val boardHeight = BLOCKS_SIZE * ch * 2f
                    val trayHeight = cw * 4.2f
                    val top = ((size.height - boardHeight - trayHeight) / 2f).coerceAtLeast(12f)
                    val cx = size.width / 2f
                    val tray = top + boardHeight + cw * 1.25f
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF11172B), Color(0xFF171D36), Color(0xFF101522))), size = size)
                    drawCircle(Color(0xFF6A5CFF).copy(alpha = 0.10f), radius = size.width * 0.46f, center = Offset(cx, top + boardHeight * 0.48f))
                    for (y in 0 until BLOCKS_SIZE) for (x in 0 until BLOCKS_SIZE) {
                        val idx = y * BLOCKS_SIZE + x
                        val center = isoCenter(x, y, cx, top, cw, ch)
                        drawCube(center, cw * 0.88f, ch * 0.88f, cw * 0.20f, if (board[idx] >= 0) blockColors[board[idx] % blockColors.size] else Color(0xFF2C3555).copy(alpha = 0.82f), board[idx] >= 0)
                    }
                    pieces.forEachIndexed { i, shape ->
                        if (shape.cells.isNotEmpty()) {
                            val trayCenter = Offset(size.width * (i + 0.5f) / 3f, tray + cw * 0.8f)
                            val maxX = (shape.cells.maxOfOrNull { it.first } ?: 0) + 1
                            val maxY = (shape.cells.maxOfOrNull { it.second } ?: 0) + 1
                            val pw = cw * 0.40f
                            val ph = ch * 0.82f
                            val startX = trayCenter.x - (maxX - maxY) * pw / 2f
                            val startY = trayCenter.y - (maxX + maxY) * ph / 2f
                            if (i == selected) {
                                drawRoundRect(Color(0xFF8B80FF).copy(alpha = 0.18f), topLeft = Offset(size.width * i / 3f + 6f, tray - cw * 0.15f), size = androidx.compose.ui.geometry.Size(size.width / 3f - 12f, cw * 3.1f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(cw * 0.28f))
                                drawRoundRect(Color(0xFF9C92FF), topLeft = Offset(size.width * i / 3f + 6f, tray - cw * 0.15f), size = androidx.compose.ui.geometry.Size(size.width / 3f - 12f, cw * 3.1f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(cw * 0.28f), style = Stroke(2f))
                            }
                            shape.cells.forEach { (dx, dy) ->
                                val center = Offset(startX + (dx - dy) * pw, startY + (dx + dy) * ph)
                                drawCube(center, pw * 0.86f, ph * 0.9f, pw * 0.24f, shape.color, true)
                            }
                        }
                    }
                }
                if (gameOver) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(18.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = Color(0xF21B2038),
                        tonalElevation = 8.dp,
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("انتهت الجولة", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("نتيجتك: $score", color = Color(0xFFBFC8FF), fontSize = 16.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { resetGame() }) { Text("العب مجددًا") }
                        }
                    }
                }
                TextButton(onClick = { resetGame() }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                    Text("إعادة اللعب")
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun randomBlock(): BlockShape {
    val shape = blockShapes[Random.nextInt(blockShapes.size)]
    return BlockShape(shape, blockColors[Random.nextInt(blockColors.size)])
}

private fun isoCenter(x: Int, y: Int, cx: Float, top: Float, cw: Float, ch: Float): Offset =
    Offset(cx + (x - y) * cw / 2f, top + (x + y) * ch)

private fun DrawScope.drawCube(center: Offset, width: Float, height: Float, depth: Float, color: Color, solid: Boolean) {
    val top = Path().apply {
        moveTo(center.x, center.y - height / 2f)
        lineTo(center.x + width / 2f, center.y)
        lineTo(center.x, center.y + height / 2f)
        lineTo(center.x - width / 2f, center.y)
        close()
    }
    val left = Path().apply {
        moveTo(center.x - width / 2f, center.y)
        lineTo(center.x, center.y + height / 2f)
        lineTo(center.x, center.y + height / 2f + depth)
        lineTo(center.x - width / 2f, center.y + depth)
        close()
    }
    val right = Path().apply {
        moveTo(center.x + width / 2f, center.y)
        lineTo(center.x, center.y + height / 2f)
        lineTo(center.x, center.y + height / 2f + depth)
        lineTo(center.x + width / 2f, center.y + depth)
        close()
    }
    drawPath(left, if (solid) color.copy(alpha = 0.72f) else color.copy(alpha = 0.55f))
    drawPath(right, if (solid) color.copy(alpha = 0.48f) else color.copy(alpha = 0.34f))
    drawPath(top, Brush.linearGradient(listOf(color.copy(alpha = if (solid) 1f else 0.68f), color.copy(alpha = if (solid) 0.76f else 0.42f)), start = Offset(center.x - width / 2f, center.y - height / 2f), end = Offset(center.x + width / 2f, center.y + height / 2f)))
    drawPath(top, Color.White.copy(alpha = if (solid) 0.22f else 0.10f), style = Stroke(width = 1.2f))
}
