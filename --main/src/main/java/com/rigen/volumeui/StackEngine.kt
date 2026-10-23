package com.rigen.volumeui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin

/*
 * Stack - rules and physics only. Units are "tiles"; the base layer is 1 x 1.
 * A layer slides back and forth along one axis; dropping it keeps only the part that overlaps the layer below.
 */

internal class StackLayer(val cx: Float, val cz: Float, val sx: Float, val sz: Float, val color: Int)

/** A sliced-off piece that falls away. [y] is how far it has dropped below its layer. */
internal class StackDebris(val cx: Float, val cz: Float, val sx: Float, val sz: Float, val level: Int, val color: Int) {
    var y = 0f
    var vy = 0f
}

internal class StackEngine {
    val layers = ArrayList<StackLayer>()
    val debris = ArrayList<StackDebris>()
    /** 0 = the moving layer slides along x, 1 = along z. */
    var axis = 0
        private set
    private var phase = START_PHASE
    var score = 0
        private set
    var combo = 0
        private set
    var over = false
        private set
    /** Set by [drop]: whether the last drop was perfect. The screen reads it for its "Perfect" flash. */
    var lastPerfect = false
        private set
    private var colorCursor = 0

    companion object {
        const val BASE = 1f
        const val AMP = 1.45f
        const val TOLERANCE = 0.05f
        const val MIN_SIZE = 0.05f
        const val GROW = 0.04f
        const val START_PHASE = (-PI / 2).toFloat()
        const val GRAVITY = 16f
    }

    init {
        reset()
    }

    fun reset() {
        layers.clear()
        debris.clear()
        layers.add(StackLayer(0f, 0f, BASE, BASE, 0))
        axis = 0
        phase = START_PHASE
        score = 0
        combo = 0
        over = false
        lastPerfect = false
        colorCursor = 0
    }

    val top: StackLayer get() = layers.last()

    /** Sliding speed in radians per second: faster the higher the tower. */
    val speed: Float get() = (2.1f + 0.055f * score).coerceAtMost(4.6f)

    /** Offset of the moving layer from the centre of the layer below, along [axis]. */
    val offset: Float get() = AMP * sin(phase)

    /** The layer that is currently sliding. */
    fun moving(): StackLayer {
        val t = top
        val o = offset
        return if (axis == 0) StackLayer(t.cx + o, t.cz, t.sx, t.sz, nextColor()) else StackLayer(t.cx, t.cz + o, t.sx, t.sz, nextColor())
    }

    private fun nextColor() = (layers.size) % 1000

    fun tick(dt: Float) {
        if (!over) phase += speed * dt
        val iter = debris.iterator()
        while (iter.hasNext()) {
            val d = iter.next()
            d.vy += GRAVITY * dt
            d.y += d.vy * dt
            if (d.y > 12f) iter.remove()
        }
    }

    /** Drops the moving layer. Returns false if the game is already over. */
    fun drop(): Boolean {
        if (over) return false
        val t = top
        val off = offset
        val size = if (axis == 0) t.sx else t.sz
        val cut = abs(off)
        val level = layers.size
        val color = nextColor()
        lastPerfect = false

        if (cut >= size - MIN_SIZE) {
            // Missed: the whole layer falls.
            val m = moving()
            debris.add(StackDebris(m.cx, m.cz, m.sx, m.sz, level, color))
            over = true
            combo = 0
            return true
        }

        val layer: StackLayer
        if (cut <= TOLERANCE) {
            // Perfect: snap into place, and after a few in a row the layer grows back a little.
            combo++
            lastPerfect = true
            val grow = if (combo >= 3) GROW else 0f
            layer = StackLayer(
                t.cx, t.cz,
                (t.sx + grow).coerceAtMost(BASE), (t.sz + grow).coerceAtMost(BASE),
                color,
            )
        } else {
            combo = 0
            val newCenter = (if (axis == 0) t.cx else t.cz) + off / 2f
            val cutCenter = (if (axis == 0) t.cx else t.cz) + off / 2f + sign(off) * size / 2f
            if (axis == 0) {
                layer = StackLayer(newCenter, t.cz, size - cut, t.sz, color)
                debris.add(StackDebris(cutCenter, t.cz, cut, t.sz, level, color))
            } else {
                layer = StackLayer(t.cx, newCenter, t.sx, size - cut, color)
                debris.add(StackDebris(t.cx, cutCenter, t.sx, cut, level, color))
            }
        }
        layers.add(layer)
        score++
        axis = 1 - axis
        phase = START_PHASE
        return true
    }
}
