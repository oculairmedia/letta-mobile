package com.letta.mobile.data.memory.graph.barneshut

import kotlin.math.max
import kotlin.math.sqrt

/** The bodies a repulsion pass reads: positions, charges and (optionally) collision radii, by index. */
class ForceBodies(
    val x: FloatArray,
    val y: FloatArray,
    val charge: FloatArray,
    val radii: FloatArray?,
)

/** Where a repulsion pass adds each body's force. */
class ForceSums(val fx: FloatArray, val fy: FloatArray)

/** How overlapping circles push apart: padding beyond touching, and force per unit of overlap. */
class CollisionTerms(val padding: Float, val strength: Float)

/**
 * The repulsion a pass applies: [strength] over squared distance times both charges, none beyond
 * the squared cut-off [cutoffSq] (0: no cut-off), [collision] between nearby circles, and the
 * Barnes–Hut opening angle [theta].
 */
class RepulsionTerms(val strength: Float, val cutoffSq: Float, val collision: CollisionTerms, val theta: Float)

/**
 * Many-body repulsion for a big graph in about n log n instead of n squared: bodies go into a
 * quadtree whose cells hold their total charge and charge-weighted centre, and each body feels a
 * far cell (one whose size against its distance is under theta) as a single body. Near bodies are
 * still counted pair by pair, collision included. Every buffer is allocated once for [capacity]
 * bodies and reused, so a pass allocates nothing.
 */
class BarnesHutTree(private val capacity: Int) {
    private val cells = BarnesHutCells(capacity)
    private val stack = IntArray(capacity * CELLS_PER_BODY + ROOT_CELLS)

    // The pass in progress.
    private var bodies = ForceBodies(FloatArray(0), FloatArray(0), FloatArray(0), null)
    private var terms = RepulsionTerms(0f, 0f, CollisionTerms(0f, 0f), 0f)
    private var sums = ForceSums(FloatArray(0), FloatArray(0))

    /** Adds every body's repulsion (and nearby collision) from every other body to [sums]. */
    fun repel(bodies: ForceBodies, terms: RepulsionTerms, sums: ForceSums) {
        val count = minOf(capacity, bodies.x.size)
        if (count < 2) return
        this.bodies = bodies
        this.terms = terms
        this.sums = sums
        cells.build(bodies, count)
        for (i in 0 until count) repelOne(i)
    }

    private fun repelOne(i: Int) {
        var top = 0
        stack[top++] = 0
        val thetaSq = terms.theta * terms.theta
        while (top > 0) {
            val cell = stack[--top]
            if (cells.cellBody[cell] != INTERNAL) {
                repelLeaf(i, cell)
                continue
            }
            val dx = bodies.x[i] - cells.cellCx[cell]
            val dy = bodies.y[i] - cells.cellCy[cell]
            val distanceSq = dx * dx + dy * dy
            val size = cells.cellSize[cell]
            if (size * size < thetaSq * distanceSq) {
                far(i, dx, dy, cells.cellCharge[cell])
            } else {
                top = pushChildren(cell, top)
            }
        }
    }

    private fun pushChildren(cell: Int, start: Int): Int {
        var top = start
        for (q in 0 until QUADRANTS) {
            val child = cells.cellChild[cell * QUADRANTS + q]
            if (child != EMPTY) stack[top++] = child
        }
        return top
    }

    private fun repelLeaf(i: Int, cell: Int) {
        var body = cells.cellBody[cell]
        while (body != EMPTY) {
            if (body != i) pair(i, body)
            body = cells.nextBody[body]
        }
    }

    /** One near pair, as the every-pair pass counts it: repulsion plus collision, on body [i] only. */
    private fun pair(i: Int, j: Int) {
        var dx = bodies.x[i] - bodies.x[j]
        var dy = bodies.y[i] - bodies.y[j]
        if (dx == 0f && dy == 0f) {
            // Coincident: separate along a deterministic axis, as the every-pair pass does.
            dx = (j - i) * COINCIDENT_NUDGE
            dy = (minOf(i, j) + 1) * COINCIDENT_NUDGE * (if (i < j) 1f else -1f)
        }
        val distanceSq = max(dx * dx + dy * dy, MIN_DISTANCE_SQ)
        val distance = sqrt(distanceSq)
        val radii = bodies.radii
        val collision = terms.collision
        val overlap = radii?.let { r -> max(0f, r[i] + r[j] + collision.padding - distance) * collision.strength } ?: 0f
        val repels = terms.cutoffSq == 0f || distanceSq <= terms.cutoffSq
        val force = (if (repels) terms.strength * (bodies.charge[i] * bodies.charge[j]) / distanceSq else 0f) + overlap
        sums.fx[i] += dx / distance * force
        sums.fy[i] += dy / distance * force
    }

    /** A far cell felt as one body of [charge] at offset ([dx], [dy]) from body [i]. */
    private fun far(i: Int, dx: Float, dy: Float, charge: Float) {
        val distanceSq = max(dx * dx + dy * dy, MIN_DISTANCE_SQ)
        if (terms.cutoffSq != 0f && distanceSq > terms.cutoffSq) return
        val distance = sqrt(distanceSq)
        val force = terms.strength * bodies.charge[i] * charge / distanceSq
        sums.fx[i] += dx / distance * force
        sums.fy[i] += dy / distance * force
    }

    private companion object {
        const val QUADRANTS = 4
        const val CELLS_PER_BODY = 25
        const val ROOT_CELLS = 16
        const val EMPTY = -1
        const val INTERNAL = -2
        const val MIN_DISTANCE_SQ = 1f
        const val COINCIDENT_NUDGE = 0.5f
    }
}
