package com.rigen.volumeui

import kotlin.random.Random

/*
 * 2048 - rules only (no Android or Compose), so they can be tested on a plain JVM.
 * A tile is stored as its exponent: 0 = empty, 1 = 2, 2 = 4, ... 11 = 2048.
 */

internal const val G2048_N = 4

internal const val DIR_LEFT = 0
internal const val DIR_UP = 1
internal const val DIR_RIGHT = 2
internal const val DIR_DOWN = 3

/** One tile sliding from cell [from] to cell [to]. [exp] is its value before any merge. */
internal class G2048Move(val from: Int, val to: Int, val exp: Int)

internal class G2048Result(
    val moved: Boolean,
    val gained: Int,
    val moves: List<G2048Move>,
    /** Cells that now hold a freshly merged tile. */
    val merged: Set<Int>,
    /** Cell of the new random tile, or -1. */
    val spawned: Int,
)

internal class Game2048Engine(private val rng: Random = Random.Default) {
    val cells = IntArray(G2048_N * G2048_N)
    var score = 0
        private set
    /** True once a 2048 tile has been made; the game goes on after that. */
    var won = false
        private set
    var over = false
        private set

    init {
        reset()
    }

    fun reset() {
        cells.fill(0)
        score = 0
        won = false
        over = false
        spawn()
        spawn()
    }

    private fun spawn(): Int {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return -1
        val i = empty[rng.nextInt(empty.size)]
        cells[i] = if (rng.nextInt(10) == 0) 2 else 1
        return i
    }

    /** The four cell indices of line [r] in the order tiles travel towards the wall. */
    private fun line(dir: Int, r: Int): IntArray = IntArray(G2048_N) { k ->
        when (dir) {
            DIR_LEFT -> r * G2048_N + k
            DIR_RIGHT -> r * G2048_N + (G2048_N - 1 - k)
            DIR_UP -> k * G2048_N + r
            else -> (G2048_N - 1 - k) * G2048_N + r
        }
    }

    fun move(dir: Int): G2048Result {
        if (over) return G2048Result(false, 0, emptyList(), emptySet(), -1)
        val before = cells.copyOf()
        val next = IntArray(cells.size)
        val moves = ArrayList<G2048Move>()
        val merged = HashSet<Int>()
        var gained = 0
        for (r in 0 until G2048_N) {
            val idx = line(dir, r)
            val mergedHere = BooleanArray(G2048_N)
            var k = 0
            for (p in 0 until G2048_N) {
                val v = before[idx[p]]
                if (v == 0) continue
                if (k > 0 && next[idx[k - 1]] == v && !mergedHere[k - 1]) {
                    next[idx[k - 1]] = v + 1
                    mergedHere[k - 1] = true
                    gained += 1 shl (v + 1)
                    moves.add(G2048Move(idx[p], idx[k - 1], v))
                    merged.add(idx[k - 1])
                } else {
                    next[idx[k]] = v
                    moves.add(G2048Move(idx[p], idx[k], v))
                    k++
                }
            }
        }
        if (next.contentEquals(before)) return G2048Result(false, 0, emptyList(), emptySet(), -1)
        next.copyInto(cells)
        score += gained
        if (cells.any { it >= 11 }) won = true
        val spawned = spawn()
        if (!canMove()) over = true
        return G2048Result(true, gained, moves, merged, spawned)
    }

    fun canMove(): Boolean {
        if (cells.any { it == 0 }) return true
        for (y in 0 until G2048_N) for (x in 0 until G2048_N) {
            val v = cells[y * G2048_N + x]
            if (x + 1 < G2048_N && cells[y * G2048_N + x + 1] == v) return true
            if (y + 1 < G2048_N && cells[(y + 1) * G2048_N + x] == v) return true
        }
        return false
    }

    fun encode(): String =
        listOf("v1", cells.joinToString("") { Character.forDigit(it, 36).toString() }, score.toString(), if (won) "1" else "0", if (over) "1" else "0")
            .joinToString("|")

    fun decode(text: String?): Boolean {
        fun fail(): Boolean { reset(); return false }
        if (text.isNullOrEmpty()) return fail()
        return try {
            val p = text.split("|")
            if (p.size != 5 || p[0] != "v1" || p[1].length != cells.size) return fail()
            val tmp = IntArray(cells.size)
            for (i in tmp.indices) {
                val v = Character.digit(p[1][i], 36)
                if (v !in 0..17) return fail()
                tmp[i] = v
            }
            val sc = p[2].toInt()
            if (sc < 0) return fail()
            tmp.copyInto(cells)
            score = sc
            won = p[3] == "1"
            over = p[4] == "1"
            if (!over && !canMove()) over = true
            true
        } catch (e: Exception) {
            fail()
        }
    }
}
