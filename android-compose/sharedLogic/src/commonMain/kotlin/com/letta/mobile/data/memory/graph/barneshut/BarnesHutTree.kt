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
    private val maxCells = capacity * CELLS_PER_BODY + ROOT_CELLS
    private val cellX = FloatArray(maxCells)
    private val cellY = FloatArray(maxCells)
    private val cellSize = FloatArray(maxCells)
    private val cellCharge = FloatArray(maxCells)
    private val cellCx = FloatArray(maxCells)
    private val cellCy = FloatArray(maxCells)
    private val cellChild = IntArray(maxCells * QUADRANTS)
    private val cellBody = IntArray(maxCells)
    private val cellDepth = IntArray(maxCells)
    private val nextBody = IntArray(capacity)
    private val stack = IntArray(maxCells)
    private var cells = 0

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
        build(count)
        for (i in 0 until count) repelOne(i)
    }

    private fun build(count: Int) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            minX = minOf(minX, bodies.x[i])
            minY = minOf(minY, bodies.y[i])
            maxX = maxOf(maxX, bodies.x[i])
            maxY = maxOf(maxY, bodies.y[i])
        }
        cells = 0
        val root = newCell(minX, minY, max(max(maxX - minX, maxY - minY), MIN_CELL), depth = 0)
        for (i in 0 until count) insert(root, i)
        summarise()
    }

    private fun newCell(x: Float, y: Float, size: Float, depth: Int): Int {
        val cell = cells++
        cellX[cell] = x
        cellY[cell] = y
        cellSize[cell] = size
        cellBody[cell] = EMPTY
        cellDepth[cell] = depth
        for (q in 0 until QUADRANTS) cellChild[cell * QUADRANTS + q] = EMPTY
        return cell
    }

    /** Drops body [i] into the tree under [start]; a crowded or too-deep leaf keeps a chain of bodies. */
    private fun insert(start: Int, i: Int) {
        var cell = start
        nextBody[i] = EMPTY
        while (true) {
            if (cellBody[cell] == INTERNAL) {
                cell = childFor(cell, bodies.x[i], bodies.y[i])
                continue
            }
            val resident = cellBody[cell]
            if (resident == EMPTY) {
                cellBody[cell] = i
                return
            }
            if (cellDepth[cell] >= MAX_DEPTH || cells + QUADRANTS > maxCells) {
                nextBody[i] = resident
                cellBody[cell] = i
                return
            }
            // Split: the resident chain moves down into its quadrants, then this body continues.
            cellBody[cell] = INTERNAL
            var moving = resident
            while (moving != EMPTY) {
                val after = nextBody[moving]
                insert(childFor(cell, bodies.x[moving], bodies.y[moving]), moving)
                moving = after
            }
        }
    }

    private fun childFor(cell: Int, px: Float, py: Float): Int {
        val half = cellSize[cell] / 2f
        val right = px >= cellX[cell] + half
        val below = py >= cellY[cell] + half
        val quadrant = (if (right) 1 else 0) + (if (below) 2 else 0)
        val slot = cell * QUADRANTS + quadrant
        if (cellChild[slot] == EMPTY) {
            val x = if (right) cellX[cell] + half else cellX[cell]
            val y = if (below) cellY[cell] + half else cellY[cell]
            cellChild[slot] = newCell(x, y, half, cellDepth[cell] + 1)
        }
        return cellChild[slot]
    }

    /** Every cell's total charge and charge-weighted centre, children before parents. */
    private fun summarise() {
        for (cell in cells - 1 downTo 0) {
            var charge = 0f
            var sx = 0f
            var sy = 0f
            if (cellBody[cell] == INTERNAL) {
                for (q in 0 until QUADRANTS) {
                    val child = cellChild[cell * QUADRANTS + q]
                    if (child == EMPTY) continue
                    charge += cellCharge[child]
                    sx += cellCx[child] * cellCharge[child]
                    sy += cellCy[child] * cellCharge[child]
                }
            } else {
                var body = cellBody[cell]
                while (body != EMPTY) {
                    charge += bodies.charge[body]
                    sx += bodies.x[body] * bodies.charge[body]
                    sy += bodies.y[body] * bodies.charge[body]
                    body = nextBody[body]
                }
            }
            cellCharge[cell] = charge
            cellCx[cell] = if (charge != 0f) sx / charge else cellX[cell] + cellSize[cell] / 2f
            cellCy[cell] = if (charge != 0f) sy / charge else cellY[cell] + cellSize[cell] / 2f
        }
    }

    private fun repelOne(i: Int) {
        var top = 0
        stack[top++] = 0
        val thetaSq = terms.theta * terms.theta
        while (top > 0) {
            val cell = stack[--top]
            if (cellBody[cell] != INTERNAL) {
                var body = cellBody[cell]
                while (body != EMPTY) {
                    if (body != i) pair(i, body)
                    body = nextBody[body]
                }
                continue
            }
            val dx = bodies.x[i] - cellCx[cell]
            val dy = bodies.y[i] - cellCy[cell]
            val distanceSq = dx * dx + dy * dy
            val size = cellSize[cell]
            if (size * size < thetaSq * distanceSq) {
                far(i, dx, dy, cellCharge[cell])
            } else {
                for (q in 0 until QUADRANTS) {
                    val child = cellChild[cell * QUADRANTS + q]
                    if (child != EMPTY) stack[top++] = child
                }
            }
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
        const val MAX_DEPTH = 24
        const val EMPTY = -1
        const val INTERNAL = -2
        const val MIN_CELL = 1f
        const val MIN_DISTANCE_SQ = 1f
        const val COINCIDENT_NUDGE = 0.5f
    }
}
