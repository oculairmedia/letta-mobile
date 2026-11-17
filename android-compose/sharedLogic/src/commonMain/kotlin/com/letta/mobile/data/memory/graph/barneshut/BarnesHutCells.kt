package com.letta.mobile.data.memory.graph.barneshut

import kotlin.math.max

/** Reusable quadtree cells, built and summarised before a repulsion traversal. */
internal class BarnesHutCells(private val capacity: Int) {
    private val maxCells = capacity * CELLS_PER_BODY + ROOT_CELLS
    private val cellX = FloatArray(maxCells)
    private val cellY = FloatArray(maxCells)
    val cellSize = FloatArray(maxCells)
    val cellCharge = FloatArray(maxCells)
    val cellCx = FloatArray(maxCells)
    val cellCy = FloatArray(maxCells)
    val cellChild = IntArray(maxCells * QUADRANTS)
    val cellBody = IntArray(maxCells)
    private val cellDepth = IntArray(maxCells)
    val nextBody = IntArray(capacity)
    private var cells = 0

    private lateinit var bodies: ForceBodies
    private val summary = ChargeMoment()
    private val bounds = CellBounds()

    fun build(bodies: ForceBodies, count: Int) {
        this.bodies = bodies
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
        val root = newCell(bounds.set(minX, minY, max(max(maxX - minX, maxY - minY), MIN_CELL)), CellIndex(EMPTY))
        for (i in 0 until count) insert(root, BodyIndex(i))
        summarise()
    }

    private fun newCell(bounds: CellBounds, parent: CellIndex): CellIndex {
        val cell = cells++
        cellX[cell] = bounds.x
        cellY[cell] = bounds.y
        cellSize[cell] = bounds.size
        cellBody[cell] = EMPTY
        cellDepth[cell] = if (parent.value == EMPTY) 0 else cellDepth[parent.value] + 1
        for (q in 0 until QUADRANTS) cellChild[cell * QUADRANTS + q] = EMPTY
        return CellIndex(cell)
    }

    /** Drops body [i] into the tree under [start]; a crowded or too-deep leaf keeps a chain of bodies. */
    private fun insert(start: CellIndex, i: BodyIndex) {
        var cell = start
        nextBody[i.value] = EMPTY
        while (true) {
            if (cellBody[cell.value] == INTERNAL) {
                cell = childFor(cell, i)
                continue
            }
            val resident = cellBody[cell.value]
            if (resident == EMPTY) {
                cellBody[cell.value] = i.value
                return
            }
            if (cellDepth[cell.value] >= MAX_DEPTH || cells + QUADRANTS > maxCells) {
                nextBody[i.value] = resident
                cellBody[cell.value] = i.value
                return
            }
            // Split: the resident chain moves down into its quadrants, then this body continues.
            cellBody[cell.value] = INTERNAL
            var moving = resident
            while (moving != EMPTY) {
                val after = nextBody[moving]
                insert(childFor(cell, BodyIndex(moving)), BodyIndex(moving))
                moving = after
            }
        }
    }

    private fun childFor(cell: CellIndex, body: BodyIndex): CellIndex {
        val px = bodies.x[body.value]
        val py = bodies.y[body.value]
        val half = cellSize[cell.value] / 2f
        val right = px >= cellX[cell.value] + half
        val below = py >= cellY[cell.value] + half
        val quadrant = (if (right) 1 else 0) + (if (below) 2 else 0)
        val slot = cell.value * QUADRANTS + quadrant
        if (cellChild[slot] == EMPTY) {
            val x = if (right) cellX[cell.value] + half else cellX[cell.value]
            val y = if (below) cellY[cell.value] + half else cellY[cell.value]
            cellChild[slot] = newCell(bounds.set(x, y, half), cell).value
        }
        return CellIndex(cellChild[slot])
    }

    /** Every cell's total charge and charge-weighted centre, children before parents. */
    private fun summarise() {
        for (cell in cells - 1 downTo 0) summariseCell(CellIndex(cell))
    }

    private fun summariseCell(cell: CellIndex) {
        val total = summary
        total.clear()
        if (cellBody[cell.value] == INTERNAL) summariseChildren(cell, total) else summariseBodies(cell, total)
        val charge = total.charge
        val sx = total.x
        val sy = total.y
        cellCharge[cell.value] = charge
        cellCx[cell.value] = if (charge != 0f) sx / charge else cellX[cell.value] + cellSize[cell.value] / 2f
        cellCy[cell.value] = if (charge != 0f) sy / charge else cellY[cell.value] + cellSize[cell.value] / 2f
    }

    private fun summariseChildren(cell: CellIndex, total: ChargeMoment) {
        for (q in 0 until QUADRANTS) {
            val child = cellChild[cell.value * QUADRANTS + q]
            if (child == EMPTY) continue
            total.addCell(this, CellIndex(child))
        }
    }

    private fun summariseBodies(cell: CellIndex, total: ChargeMoment) {
        var body = cellBody[cell.value]
        while (body != EMPTY) {
            total.addBody(bodies, BodyIndex(body))
            body = nextBody[body]
        }
    }

    private companion object {
        const val QUADRANTS = 4
        const val CELLS_PER_BODY = 25
        const val ROOT_CELLS = 16
        const val MAX_DEPTH = 24
        const val EMPTY = -1
        const val INTERNAL = -2
        const val MIN_CELL = 1f
    }
}

/** A reusable cell's charge and first moment; additions retain traversal order. */
internal class ChargeMoment {
    var charge = 0f
        private set
    var x = 0f
        private set
    var y = 0f
        private set

    fun clear() {
        charge = 0f
        x = 0f
        y = 0f
    }

    fun addCell(cells: BarnesHutCells, cell: CellIndex) {
        charge += cells.cellCharge[cell.value]
        x += cells.cellCx[cell.value] * cells.cellCharge[cell.value]
        y += cells.cellCy[cell.value] * cells.cellCharge[cell.value]
    }

    fun addBody(bodies: ForceBodies, body: BodyIndex) {
        charge += bodies.charge[body.value]
        x += bodies.x[body.value] * bodies.charge[body.value]
        y += bodies.y[body.value] * bodies.charge[body.value]
    }
}

/** An index into the reusable cell buffers. */
@kotlin.jvm.JvmInline
internal value class CellIndex(val value: Int)

/** An index into the force body's position and charge buffers. */
@kotlin.jvm.JvmInline
internal value class BodyIndex(val value: Int)

/** A quadtree cell's lower corner and side length. */
internal class CellBounds {
    var x = 0f
        private set
    var y = 0f
        private set
    var size = 0f
        private set

    fun set(x: Float, y: Float, size: Float): CellBounds {
        this.x = x
        this.y = y
        this.size = size
        return this
    }
}
