package com.rigen.volumeui

import kotlin.random.Random

/* Memory (matching pairs) - rules only. */

internal class MemoryLevel(val cols: Int, val rows: Int) {
    val pairs: Int get() = cols * rows / 2
}

internal val MEMORY_LEVELS = listOf(MemoryLevel(4, 3), MemoryLevel(4, 4), MemoryLevel(5, 4), MemoryLevel(6, 5))

internal const val CARD_HIDDEN = 0
internal const val CARD_UP = 1
internal const val CARD_MATCHED = 2

internal enum class Flip { IGNORED, FIRST, MATCH, MISMATCH }

internal class MemoryEngine(val level: MemoryLevel, rng: Random = Random.Default) {
    /** Pair id of every card. Two cards with the same id are a pair. */
    val cards: IntArray
    val state: IntArray
    var moves = 0
        private set
    var matched = 0
        private set
    private var first = -1
    private var second = -1

    init {
        require(level.cols * level.rows % 2 == 0)
        val ids = ArrayList<Int>()
        for (p in 0 until level.pairs) { ids.add(p); ids.add(p) }
        ids.shuffle(rng)
        cards = ids.toIntArray()
        state = IntArray(cards.size)
    }

    val done: Boolean get() = matched == level.pairs

    /** True while two different cards are face up and wait to be turned back. */
    val waiting: Boolean get() = second != -1

    fun flip(i: Int): Flip {
        if (i !in cards.indices || state[i] != CARD_HIDDEN || second != -1) return Flip.IGNORED
        if (first == -1) {
            first = i
            state[i] = CARD_UP
            return Flip.FIRST
        }
        second = i
        state[i] = CARD_UP
        moves++
        if (cards[first] == cards[second]) {
            state[first] = CARD_MATCHED
            state[second] = CARD_MATCHED
            matched++
            first = -1
            second = -1
            return Flip.MATCH
        }
        return Flip.MISMATCH
    }

    /** Turns the two mismatched cards back down. */
    fun hideMismatch() {
        if (second == -1) return
        state[first] = CARD_HIDDEN
        state[second] = CARD_HIDDEN
        first = -1
        second = -1
    }

    /** 3 stars for a near-perfect game, then 2, then 1. */
    fun stars(): Int = when {
        moves <= level.pairs * 3 / 2 -> 3
        moves <= level.pairs * 9 / 4 -> 2
        else -> 1
    }
}
