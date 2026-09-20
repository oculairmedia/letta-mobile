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
        var index = 0
        while (index + TabletBridge.STRIDE <= events.size) {
            val sample = TabletPenDecoder.decodeSample(events, index, scale)
            index += TabletBridge.STRIDE
            if (sample.force != TabletBridge.NO_PRESSURE) _pressure.value = sample.force

            if (offerToCanvas(sample)) continue
            awtMapper.dispatch(target, sample, scale)
        }
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
