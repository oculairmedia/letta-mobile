package com.letta.mobile.desktop.input

import com.letta.mobile.ui.canvas.CanvasPenEvent
import com.letta.mobile.ui.canvas.CanvasPenTool

/**
 * Decodes raw stride data from the native tablet bridge into typed events and canvas events.
 */
internal object TabletPenDecoder {

    data class DecodedSample(
        val kind: Int,
        val x: Float,
        val y: Float,
        val rawX: Float,
        val rawY: Float,
        val force: Float,
        val tool: Int,
    )

    fun decodeSample(events: FloatArray, index: Int, scale: Double): DecodedSample {
        val rawX = events[index + 1]
        val rawY = events[index + 2]
        return DecodedSample(
            kind = events[index].toInt(),
            x = (rawX / scale).toFloat(),
            y = (rawY / scale).toFloat(),
            rawX = rawX,
            rawY = rawY,
            force = events[index + 3],
            tool = events[index + 4].toInt(),
        )
    }

    fun toCanvasEvent(sample: DecodedSample): CanvasPenEvent? {
        val phase = when (sample.kind) {
            TabletBridge.KIND_DOWN -> CanvasPenEvent.Phase.DOWN
            TabletBridge.KIND_UP -> CanvasPenEvent.Phase.UP
            TabletBridge.KIND_MOVE -> CanvasPenEvent.Phase.MOVE
            TabletBridge.KIND_IN -> CanvasPenEvent.Phase.IN
            TabletBridge.KIND_OUT -> CanvasPenEvent.Phase.OUT
            else -> return null
        }
        val penTool = when (sample.tool) {
            TabletBridge.TOOL_DRAW -> CanvasPenTool.DRAW
            TabletBridge.TOOL_ERASER -> CanvasPenTool.ERASER
            else -> CanvasPenTool.OTHER
        }
        return CanvasPenEvent(
            phase = phase,
            x = sample.x,
            y = sample.y,
            pressure = sample.force.takeIf { it != TabletBridge.NO_PRESSURE },
            tool = penTool,
        )
    }
}
