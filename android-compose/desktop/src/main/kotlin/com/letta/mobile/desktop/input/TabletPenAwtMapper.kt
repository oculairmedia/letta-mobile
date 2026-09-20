package com.letta.mobile.desktop.input

import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Maps decoded pen samples to standard AWT MouseEvents so the pen behaves like a normal pointer.
 */
internal class TabletPenAwtMapper {
    var down: Boolean = false
        internal set
    private var lastPoint: Point = Point(0, 0)
    private var reportedGeometry: Boolean = false

    fun dispatch(target: Component, sample: TabletPenDecoder.DecodedSample, scale: Double) {
        val point = Point(sample.x.toInt(), sample.y.toInt())
        reportGeometryOnce(target, sample.rawX, sample.rawY, scale, point)
        when (sample.kind) {
            TabletBridge.KIND_DOWN -> {
                down = true
                lastPoint = point
                post(target, point, MouseEvent.MOUSE_PRESSED)
            }
            TabletBridge.KIND_UP -> {
                down = false
                post(target, point, MouseEvent.MOUSE_RELEASED)
                if (point.near(lastPoint)) post(target, point, MouseEvent.MOUSE_CLICKED)
            }
            TabletBridge.KIND_MOVE ->
                post(target, point, if (down) MouseEvent.MOUSE_DRAGGED else MouseEvent.MOUSE_MOVED)
            TabletBridge.KIND_OUT -> {
                if (down) {
                    down = false
                    post(target, point, MouseEvent.MOUSE_RELEASED)
                }
                post(target, point, MouseEvent.MOUSE_EXITED)
            }
            TabletBridge.KIND_IN -> post(target, point, MouseEvent.MOUSE_ENTERED)
        }
    }

    private fun post(target: Component, point: Point, id: Int) {
        val modifiers = if (down) MouseEvent.BUTTON1_DOWN_MASK else 0
        target.dispatchEvent(
            MouseEvent(
                target,
                id,
                System.currentTimeMillis(),
                modifiers,
                point.x,
                point.y,
                if (id == MouseEvent.MOUSE_CLICKED) 1 else 0,
                false,
                if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_ENTERED || id == MouseEvent.MOUSE_EXITED) {
                    MouseEvent.NOBUTTON
                } else {
                    MouseEvent.BUTTON1
                },
            ),
        )
    }

    private fun reportGeometryOnce(target: Component, physicalX: Float, physicalY: Float, scale: Double, logical: Point) {
        if (reportedGeometry) return
        reportedGeometry = true
        val onScreen = runCatching { target.locationOnScreen }.getOrNull()
        val cursor = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
        val expectedOnScreen = onScreen?.let { Point(it.x + logical.x, it.y + logical.y) }
        println(
            "TABLET GEOMETRY: physical=($physicalX, $physicalY) scale=$scale logical=$logical " +
                "target=${target.label()} size=${target.size} locationOnScreen=$onScreen " +
                "penWouldLandAt=$expectedOnScreen osCursor=$cursor " +
                "delta=${if (expectedOnScreen != null && cursor != null) Point(cursor.x - expectedOnScreen.x, cursor.y - expectedOnScreen.y) else null}",
        )
    }

    private fun Point.near(other: Point): Boolean =
        kotlin.math.abs(x - other.x) <= TAP_SLOP && kotlin.math.abs(y - other.y) <= TAP_SLOP

    private companion object {
        const val TAP_SLOP = 4
    }
}

internal fun Component.label(): String =
    this::class.java.simpleName.ifEmpty { this::class.java.name.substringAfterLast('.') }
