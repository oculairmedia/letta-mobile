package com.letta.mobile.desktop.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.awt.Component
import java.awt.Window
import com.letta.mobile.ui.canvas.CanvasPenRegistry
import com.letta.mobile.ui.canvas.CanvasPenTarget

/**
 * Drives the pen into the application as ordinary input and delivers canvas strokes.
 */
internal class TabletPen(
    private val window: Window,
    private val penRegistry: CanvasPenRegistry,
    private val pollInterval: Long = POLL_INTERVAL_MS,
) {
    private val connection = TabletPenConnection(window)
    private val awtMapper = TabletPenAwtMapper()
    private val _pressure = MutableStateFlow(TabletBridge.NO_PRESSURE)
    private var windowLocation: java.awt.Point? = null

    val pressure: StateFlow<Float> = _pressure
    val connected: Boolean get() = connection.isConnected

    fun start(scope: CoroutineScope): Job? {
        val load = runCatching { TabletBridge.available }
        if (load.getOrDefault(false) != true) {
            println("TABLET: native library did not load: ${load.exceptionOrNull()}")
            return null
        }

        if (!connection.openTargets()) return null

        return scope.launch(Dispatchers.Main) {
            println("TABLET: polling on ${Thread.currentThread().name}")
            try {
                pollLoop()
            } finally {
                stop()
            }
        }
    }

    private suspend fun pollLoop() {
        while (coroutineContext.isActive && connection.isConnected) {
            connection.dropDeadTargets()
            if (!connection.isConnected) break
            pollActiveTargets()
            delay(pollInterval)
        }
    }

    private fun pollActiveTargets() {
        connection.handles.toList().forEach { (name, open, component) ->
            val events = runCatching { TabletBridge.nativePoll(open) }
                .onFailure { println("TABLET: poll threw: $it") }
                .getOrDefault(FloatArray(0))
            if (events.isNotEmpty()) {
                handleTargetEvents(name, events, component)
            }
        }
    }

    private fun handleTargetEvents(name: String, events: FloatArray, component: Component) {
        if (connection.sawFrom == null) {
            connection.sawFrom = name
            println("TABLET: events are coming from $name: ${events.take(16).joinToString()}")
        }
        if (connection.sawFrom == name && component.isShowing) {
            dispatchEvents(events, component)
        }
    }

    private fun dispatchEvents(events: FloatArray, target: Component) {
        val scale = runCatching {
            target.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0.0 } ?: 1.0
        }.getOrDefault(1.0)
        val windowMoved = windowMoved()
        // A drag repositions the window under a pointer that is still down. Releasing the
        // synthetic button here keeps that drag from continuing into the page.
        if (windowMoved) awtMapper.releaseIfDown(target)
        var index = 0
        while (index + TabletBridge.STRIDE <= events.size) {
            val sample = TabletPenDecoder.decodeSample(events, index, scale)
            index += TabletBridge.STRIDE
            if (sample.force != TabletBridge.NO_PRESSURE) _pressure.value = sample.force

            if (offerToCanvas(sample)) continue
            if (penSampleMirrorsToMouse(sample.tool, windowMoved)) {
                awtMapper.dispatch(target, sample, scale)
            }
        }
    }

    /**
     * True when this window's screen position changed since the previous batch.
     *
     * Dragging the window onto another display moves it under the pen. Samples from that
     * interval are still valid for a canvas stroke, but mirroring them as mouse input paints
     * presses into whichever control slid underneath.
     */
    private fun windowMoved(): Boolean {
        val location = runCatching { window.takeIf { it.isShowing }?.locationOnScreen }.getOrNull()
            ?: return false
        val moved = windowLocation != null && windowLocation != location
        windowLocation = location
        return moved
    }

    private fun offerToCanvas(sample: TabletPenDecoder.DecodedSample): Boolean {
        if (!penRegistry.hasConsumer(WindowPenTarget(window))) return false
        val canvasEvent = TabletPenDecoder.toCanvasEvent(sample) ?: return false
        val taken = runCatching {
            penRegistry.deliver(WindowPenTarget(window), canvasEvent)
        }.getOrDefault(false)
        if (taken) {
            when (sample.kind) {
                TabletBridge.KIND_DOWN -> awtMapper.down = true
                TabletBridge.KIND_UP, TabletBridge.KIND_OUT -> awtMapper.down = false
            }
        }
        return taken
    }

    fun stop() {
        connection.close()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 8L
    }
}

internal data class WindowPenTarget(val window: Window) : CanvasPenTarget

/**
 * Whether a tablet sample should also be posted as a mouse event.
 *
 * A real pen still needs that path: AWT has no stylus, so buttons and text would ignore it.
 * An emulated tool is the system mouse, which AWT already delivered. A sample that arrives
 * while the window is moving is the same pointer sliding across the page underneath the drag.
 * Mirroring either one is a second touch.
 */
internal fun penSampleMirrorsToMouse(tool: Int, windowMoved: Boolean): Boolean =
    tool != TabletBridge.TOOL_EMULATED && !windowMoved
