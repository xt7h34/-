package com.rigen.volumeui

import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

/*
 * Kanade Blocks - game rules and board geometry.
 *
 * This file has no Android or Compose dependency on purpose: the rules and the screen-to-board
 * math can be unit tested on a plain JVM, and the UI file only has to draw and forward touches.
 */

internal const val BLOCKS_SIZE = 8
internal const val BLOCKS_SLOTS = 3
internal const val BLOCKS_COLORS = 6

/** One piece: cell offsets as (x, y) with x to the right and y downwards, starting at (0, 0). */
internal class BlockShape(val cells: List<Pair<Int, Int>>) {
    val width = cells.maxOf { it.first } + 1
    val height = cells.maxOf { it.second } + 1
}

internal object BlockShapes {
    private class Family(val weight: Int, val variantIds: List<Int>)

    /** Every distinct shape, including all rotations and mirror images. */
    val all: List<BlockShape>
    private val families: List<Family>
    private val totalWeight: Int

    init {
        val shapes = ArrayList<BlockShape>()
        val seen = HashMap<Set<Pair<Int, Int>>, Int>()
        val fams = ArrayList<Family>()

        fun normalize(cells: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
            val mx = cells.minOf { it.first }
            val my = cells.minOf { it.second }
            return cells.map { (it.first - mx) to (it.second - my) }.sortedWith(compareBy({ it.second }, { it.first }))
        }

        fun family(weight: Int, base: List<Pair<Int, Int>>) {
            val ids = ArrayList<Int>()
            var current = base
            repeat(2) { mirror ->
                repeat(4) {
                    val norm = normalize(current)
                    val key = norm.toSet()
                    val id = seen.getOrPut(key) { shapes.add(BlockShape(norm)); shapes.size - 1 }
                    if (id !in ids) ids.add(id)
                    current = current.map { (x, y) -> -y to x } // rotate 90 degrees
                }
                if (mirror == 0) current = current.map { (x, y) -> -x to y }
            }
            fams.add(Family(weight, ids))
        }

        family(2, listOf(0 to 0))                                                   // dot
        family(3, listOf(0 to 0, 1 to 0))                                           // domino
        family(3, listOf(0 to 0, 1 to 0, 2 to 0))                                   // line of 3
        family(3, listOf(0 to 0, 1 to 0, 0 to 1))                                   // small corner
        family(2, listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0))                           // line of 4
        family(1, listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0))                   // line of 5
        family(3, listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1))                           // 2x2
        family(2, listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1))           // 3x2
        family(2, listOf(0 to 0, 1 to 0, 2 to 0, 1 to 1))                           // T
        family(2, listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1))                           // S / Z
        family(3, listOf(0 to 0, 0 to 1, 0 to 2, 1 to 2))                           // L / J
        family(2, listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 0 to 2))                   // big corner
        family(1, listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1, 0 to 2, 1 to 2, 2 to 2)) // 3x3

        all = shapes
        families = fams
        totalWeight = fams.sumOf { it.weight }
    }

    fun random(rng: Random): Int {
        var pick = rng.nextInt(totalWeight)
        for (f in families) {
            if (pick < f.weight) return f.variantIds[rng.nextInt(f.variantIds.size)]
            pick -= f.weight
        }
        return 0
    }
}

internal class PlaceResult(
    /** Points earned by this move. */
    val gained: Int,
    /** Rows and columns that were cleared. */
    val lines: Int,
    /** Cleared cell index -> its colour, so the screen can animate them out. */
    val cleared: Map<Int, Int>,
    /** True when the whole board is empty after the move. */
    val perfect: Boolean,
)

internal class BlocksEngine(private val rng: Random = Random.Default) {
    /** -1 for an empty cell, otherwise a colour index. */
    val board = IntArray(BLOCKS_SIZE * BLOCKS_SIZE) { -1 }
    /** Shape id of every tray slot, or -1 once the piece has been used. */
    val slotShape = IntArray(BLOCKS_SLOTS) { -1 }
    val slotColor = IntArray(BLOCKS_SLOTS)

    var score = 0
        private set
    var streak = 0
        private set
    var over = false
        private set

    init {
        reset()
    }

    fun reset() {
        board.fill(-1)
        score = 0
        streak = 0
        over = false
        refill()
    }

    fun canPlace(shapeId: Int, x: Int, y: Int): Boolean {
        if (shapeId !in BlockShapes.all.indices) return false
        return BlockShapes.all[shapeId].cells.all { (dx, dy) ->
            val px = x + dx
            val py = y + dy
            px in 0 until BLOCKS_SIZE && py in 0 until BLOCKS_SIZE && board[py * BLOCKS_SIZE + px] == -1
        }
    }

    fun fitsAnywhere(shapeId: Int): Boolean {
        if (shapeId < 0) return false
        val s = BlockShapes.all[shapeId]
        for (y in 0..BLOCKS_SIZE - s.height) for (x in 0..BLOCKS_SIZE - s.width) {
            if (canPlace(shapeId, x, y)) return true
        }
        return false
    }

    fun anyMove(): Boolean = slotShape.any { it >= 0 && fitsAnywhere(it) }

    /** Full rows and columns of a board; the cells of both are returned together. */
    private fun fullLines(b: IntArray, rows: MutableList<Int>, cols: MutableList<Int>) {
        for (r in 0 until BLOCKS_SIZE) if ((0 until BLOCKS_SIZE).all { c -> b[r * BLOCKS_SIZE + c] >= 0 }) rows.add(r)
        for (c in 0 until BLOCKS_SIZE) if ((0 until BLOCKS_SIZE).all { r -> b[r * BLOCKS_SIZE + c] >= 0 }) cols.add(c)
    }

    /** Cells that would be cleared by dropping [shapeId] at (x, y), or null if it does not fit. */
    fun wouldClear(shapeId: Int, x: Int, y: Int): Set<Int>? {
        if (!canPlace(shapeId, x, y)) return null
        val copy = board.copyOf()
        BlockShapes.all[shapeId].cells.forEach { (dx, dy) -> copy[(y + dy) * BLOCKS_SIZE + x + dx] = 0 }
        val rows = ArrayList<Int>()
        val cols = ArrayList<Int>()
        fullLines(copy, rows, cols)
        val out = HashSet<Int>()
        rows.forEach { r -> for (c in 0 until BLOCKS_SIZE) out.add(r * BLOCKS_SIZE + c) }
        cols.forEach { c -> for (r in 0 until BLOCKS_SIZE) out.add(r * BLOCKS_SIZE + c) }
        return out
    }

    /** Drops the piece in [slot] with its top-left at (x, y). Returns null if the move is not legal. */
    fun place(slot: Int, x: Int, y: Int): PlaceResult? {
        if (over || slot !in 0 until BLOCKS_SLOTS) return null
        val shapeId = slotShape[slot]
        if (shapeId < 0 || !canPlace(shapeId, x, y)) return null
        val shape = BlockShapes.all[shapeId]
        val color = slotColor[slot]
        shape.cells.forEach { (dx, dy) -> board[(y + dy) * BLOCKS_SIZE + x + dx] = color }
        var gained = shape.cells.size * 5

        // Rows and columns are measured first and cleared together, so a cell shared by a full row
        // and a full column is counted once and the second line is not broken by the first.
        val rows = ArrayList<Int>()
        val cols = ArrayList<Int>()
        fullLines(board, rows, cols)
        val lines = rows.size + cols.size
        val cleared = LinkedHashMap<Int, Int>()
        rows.forEach { r -> for (c in 0 until BLOCKS_SIZE) cleared[r * BLOCKS_SIZE + c] = board[r * BLOCKS_SIZE + c] }
        cols.forEach { c -> for (r in 0 until BLOCKS_SIZE) cleared[r * BLOCKS_SIZE + c] = board[r * BLOCKS_SIZE + c] }
        cleared.keys.forEach { board[it] = -1 }

        if (lines > 0) {
            streak++
            gained += lines * 100 + if (lines > 1) lines * 50 else 0
            if (streak > 1) gained += (streak - 1) * 50
        } else {
            streak = 0
        }
        val perfect = lines > 0 && board.all { it < 0 }
        if (perfect) gained += 500
        score += gained

        slotShape[slot] = -1
        if (slotShape.all { it < 0 }) refill()
        if (!anyMove()) over = true
        return PlaceResult(gained, lines, cleared, perfect)
    }

    /** Deals three new pieces, making sure at least one of them can still be placed. */
    private fun refill() {
        var ids = IntArray(BLOCKS_SLOTS)
        for (attempt in 0 until 60) {
            ids = IntArray(BLOCKS_SLOTS) { BlockShapes.random(rng) }
            val fits = ids.any { fitsAnywhere(it) }
            val boring = ids[0] == ids[1] && ids[1] == ids[2]
            if (fits && !(boring && attempt < 30)) break
        }
        // Last resort when 60 draws all failed: a single cell fits as long as any cell is free.
        if (ids.none { fitsAnywhere(it) } && board.any { it < 0 }) ids[0] = BlockShapes.all.indexOfFirst { it.cells.size == 1 }
        for (i in 0 until BLOCKS_SLOTS) {
            slotShape[i] = ids[i]
            slotColor[i] = rng.nextInt(BLOCKS_COLORS)
        }
    }

    /** Compact text form of the whole game, for SharedPreferences. */
    fun encode(): String {
        val cells = board.joinToString("") { if (it < 0) "." else it.toString() }
        return listOf(
            "v1", cells, slotShape.joinToString(","), slotColor.joinToString(","),
            score.toString(), streak.toString(), if (over) "1" else "0",
        ).joinToString("|")
    }

    /** Restores a game saved by [encode]. Returns false (and starts a new game) if the text is damaged. */
    fun decode(text: String?): Boolean {
        fun fail(): Boolean { reset(); return false }
        if (text.isNullOrEmpty()) return fail()
        return try {
            val p = text.split("|")
            if (p.size != 7 || p[0] != "v1" || p[1].length != BLOCKS_SIZE * BLOCKS_SIZE) return fail()
            val cells = IntArray(p[1].length)
            for (i in cells.indices) {
                val ch = p[1][i]
                val v = if (ch == '.') -1 else ch.digitToInt()
                if (v !in -1 until BLOCKS_COLORS) return fail()
                cells[i] = v
            }
            val shapes = p[2].split(",").map { it.toInt() }
            val colors = p[3].split(",").map { it.toInt() }
            if (shapes.size != BLOCKS_SLOTS || colors.size != BLOCKS_SLOTS) return fail()
            if (shapes.any { it !in -1 until BlockShapes.all.size } || colors.any { it !in 0 until BLOCKS_COLORS }) return fail()
            val sc = p[4].toInt()
            val st = p[5].toInt()
            if (sc < 0 || st < 0) return fail()
            cells.copyInto(board)
            shapes.forEachIndexed { i, v -> slotShape[i] = v }
            colors.forEachIndexed { i, v -> slotColor[i] = v }
            score = sc
            streak = st
            over = p[6] == "1"
            if (slotShape.all { it < 0 }) refill()
            if (!over && !anyMove()) over = true
            true
        } catch (e: Exception) {
            fail()
        }
    }
}

/** A point on the screen, in pixels. Kept separate from Compose's Offset so this file stays testable. */
internal data class BPoint(val x: Float, val y: Float)

/**
 * Where everything sits on the canvas. The board is drawn as a diamond: cell (gx, gy) has its centre at
 *   x = cx + (gx - gy) * a        y = boardTop + b + (gx + gy) * b
 * where a and b are the half width and half height of one tile. [toBoard] is the exact inverse, so a touch
 * is converted to the cell that is really drawn under the finger.
 */
internal class BlocksLayout(val width: Float, val height: Float) {
    val a: Float
    val b: Float
    val depth: Float
    val cx: Float = width / 2f
    val boardTop: Float
    val boardBottom: Float
    val trayTop: Float
    val trayCenterY: Float
    val trayScale = 0.58f

    init {
        val w = max(width, 1f)
        val h = max(height, 1f)
        // Height needed, in units of a, is 19.48 * ratio + 2.31 (board + gap + tray); see the constants below.
        var aa = w / 16.8f
        var ratio = ((h / aa - 2.31f) / 19.48f).coerceIn(0.5f, 0.92f)
        if (aa * (19.48f * ratio + 2.31f) > h) aa = h / (19.48f * ratio + 2.31f)
        a = aa
        b = aa * ratio
        depth = aa * 0.42f
        val boardH = 16f * b + depth
        val gap = aa * 0.9f
        val trayH = 6f * b * trayScale + depth * trayScale + aa * 0.7f
        val total = boardH + gap + trayH
        boardTop = max((h - total) * 0.45f, 0f)
        boardBottom = boardTop + boardH
        trayTop = boardBottom + gap
        trayCenterY = trayTop + trayH / 2f
    }

    fun project(gx: Float, gy: Float) = BPoint(cx + (gx - gy) * a, boardTop + b + (gx + gy) * b)

    /** Continuous board coordinates of a screen point; whole numbers are cell centres. */
    fun toBoard(px: Float, py: Float): BPoint {
        val u = (px - cx) / a
        val v = (py - boardTop - b) / b
        return BPoint((u + v) / 2f, (v - u) / 2f)
    }

    fun slotAt(px: Float, py: Float): Int {
        if (py < trayTop - a * 0.4f) return -1
        return floor(px / (width / BLOCKS_SLOTS)).toInt().coerceIn(0, BLOCKS_SLOTS - 1)
    }

    fun slotCenterX(slot: Int): Float = width * (slot + 0.5f) / BLOCKS_SLOTS

    /**
     * The board position a piece of [shape] should snap to when its centre is at screen point (px, py).
     * Looks at the nearest few cells so that a drop slightly off a legal spot still lands on it.
     * Returns null when nothing legal is close.
     */
    fun snap(engine: BlocksEngine, shapeId: Int, px: Float, py: Float): Pair<Int, Int>? {
        val shape = BlockShapes.all[shapeId]
        val g = toBoard(px, py)
        val fx = g.x - (shape.width - 1) / 2f
        val fy = g.y - (shape.height - 1) / 2f
        val bx = floor(fx + 0.5f).toInt()
        val by = floor(fy + 0.5f).toInt()
        var best: Pair<Int, Int>? = null
        var bestD = Float.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            val x = bx + dx
            val y = by + dy
            if (!engine.canPlace(shapeId, x, y)) continue
            val d = (x - fx) * (x - fx) + (y - fy) * (y - fy)
            if (d < bestD) { bestD = d; best = x to y }
        }
        return if (best != null && bestD <= 1.1f) best else null
    }

    /** Screen position of the centre of a tile when a piece is drawn around [center] at [scale]. */
    fun pieceCell(shape: BlockShape, dx: Int, dy: Int, center: BPoint, scale: Float): BPoint {
        val ox = ((dx - dy) - (shape.width - shape.height) / 2f) * a * scale
        val oy = ((dx + dy) - (shape.width + shape.height - 2) / 2f) * b * scale
        return BPoint(center.x + ox, center.y + oy)
    }
}
