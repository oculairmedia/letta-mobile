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
import java.awt.Point
import java.awt.Window
import com.letta.mobile.desktop.touch.ComposeTouchInjector
import com.letta.mobile.desktop.touch.DesktopPointerTouch
import com.letta.mobile.desktop.touch.DesktopTouchDragExclusion
import com.letta.mobile.desktop.touch.DesktopTouchOrigin
import com.letta.mobile.ui.canvas.CanvasBoardTouchRegistry
import com.letta.mobile.ui.canvas.CanvasBoardTouchResult
import com.letta.mobile.ui.canvas.CanvasBoardTouchSample
import com.letta.mobile.ui.canvas.CanvasPenRegistry
import com.letta.mobile.ui.canvas.CanvasPenTarget

/**
 * Drives the pen into the application as ordinary input and delivers canvas strokes.
 */
internal class TabletPen(
    private val window: Window,
    private val penRegistry: CanvasPenRegistry,
    private val boardTouch: CanvasBoardTouchRegistry,
    private val pollInterval: Long = POLL_INTERVAL_MS,
) {
    private val connection = TabletPenConnection(window)
    private val awtMapper = TabletPenAwtMapper()
    private val fingerPointer = TabletFingerPointer()
    private val _pressure = MutableStateFlow(TabletBridge.NO_PRESSURE)
    private var windowLocation: java.awt.Point? = null

    /**
     * The latest pose reported a pressure axis. A pen hover is `0`, not
     * [TabletBridge.NO_PRESSURE]. A finger digitizer and the system mouse report
     * [TabletBridge.NO_PRESSURE], and replaying those as mouse input is the arrow
     * cursor the user sees instead of a touch.
     */
    private var lastPoseHadPressure = false

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
        val handles = connection.handles.toList()
        // A pointer frame is copied onto every window it hits. The canvas is the one Ink
        // already talks to; until that is known, the deepest window is the board.
        val preferred = connection.sawFrom ?: handles.lastOrNull()?.first
        if (touchContacts.isEmpty()) fingerSource = preferred
        val touchSource = fingerSource ?: preferred
        val pointer = mutableListOf<Triple<String, FloatArray, java.awt.Component>>()
        handles.forEach { (name, open, component) ->
            val events = runCatching { TabletBridge.nativePoll(open) }
                .onFailure { println("TABLET: poll threw: $it") }
                .getOrDefault(FloatArray(0))
            if (events.isEmpty()) return@forEach
            if (isPointerTouch(events)) {
                pointer += Triple(name, events, component)
            } else {
                handleTargetEvents(name, events, component)
            }
        }
        // The pen's window when it has the frame; otherwise whichever window the finger
        // actually hit. Dropping the other copy was how a touch on the debug canvas never
        // became a finger and fell through to the vertical pan wheel.
        val chosen = pointer.firstOrNull { it.first == touchSource } ?: pointer.firstOrNull()
        if (chosen != null && chosen.third.isShowing) {
            dispatchEvents(chosen.second, chosen.third)
        }
    }

    /** True when the whole batch is pointer-touch, so it must not steal the pen's window. */
    private fun isPointerTouch(events: FloatArray): Boolean {
        var index = 0
        var any = false
        while (index + TabletBridge.STRIDE <= events.size) {
            val kind = events[index].toInt()
            val tool = events[index + 4].toInt()
            val touch = tool == TabletBridge.TOOL_TOUCH || kind == TabletBridge.KIND_CANCEL
            if (!touch) return false
            any = true
            index += TabletBridge.STRIDE
        }
        return any
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
        if (windowMoved) {
            awtMapper.releaseIfDown(target)
            fingerPointer.cancel()
        }
        var index = 0
        while (index + TabletBridge.STRIDE <= events.size) {
            val sample = TabletPenDecoder.decodeSample(events, index, scale)
            index += TabletBridge.STRIDE
            if (!keepsFingerOwnership(sample.tool, sample.kind, DesktopPointerTouch.ownsFingers(window))) {
                DesktopPointerTouch.release(window)
            }
            if (sample.tool == TabletBridge.TOOL_TOUCH || sample.kind == TabletBridge.KIND_CANCEL) {
                noteTouchContact(sample, scale)
                DesktopPointerTouch.markOwned(window)
                dispatchFinger(target, sample, windowMoved)
                continue
            }
            if (sample.force != TabletBridge.NO_PRESSURE) _pressure.value = sample.force
            if (sample.kind == TabletBridge.KIND_DOWN || sample.kind == TabletBridge.KIND_MOVE) {
                lastPoseHadPressure = sample.force != TabletBridge.NO_PRESSURE
            }
            if (sample.kind == TabletBridge.KIND_DOWN) {
                println("TABLET: contact tool=${sample.tool} force=${sample.force} slot=${sample.contact}")
                // A text field the pen taps into (a shape's text, the composer) needs the
                // on-screen keyboard as much as one a finger taps into. There is no other.
                if (isPenNib(sample.tool)) DesktopTouchOrigin.record(isTouch = true, atMillis = System.currentTimeMillis())
            }
            // A finger shares this bridge with the pen and has no pressure axis.
            // It must not be offered to the canvas as ink, and it must not be
            // replayed as a moving mouse cursor. A tap clicks; a drag scrolls.
            // A draw or eraser nib stays on the pen path even when its down
            // arrives before any pose; that down is not a finger.
            if (penDownWaitsForPose(sample.tool, sample.kind, sample.force)) {
                continue
            }
            // A pressureless sample that is not a nib and not a finger is an Ink phase
            // event (Up, In, Out) from a tool the pen filter rejected. Feeding it to the
            // finger pointer lifts whatever finger is already scrolling.
            if (!isPenNib(sample.tool) && !lastPoseHadPressure && sample.kind != TabletBridge.KIND_IN) {
                if (sample.kind == TabletBridge.KIND_UP || sample.kind == TabletBridge.KIND_OUT) {
                    lastPoseHadPressure = false
                }
                continue
            }

            if (sample.kind == TabletBridge.KIND_DOWN) awaitPenStroke = true
            val closingMirroredPress = awtMapper.down &&
                (sample.kind == TabletBridge.KIND_UP || sample.kind == TabletBridge.KIND_OUT)
            val taken = offerToCanvas(sample)
            if (awaitPenStroke && sample.kind == TabletBridge.KIND_MOVE && sample.force > 0f) {
                awaitPenStroke = false
                logPenStroke(target, sample, scale, taken)
            }
            if (taken) {
                if (sample.kind == TabletBridge.KIND_UP || sample.kind == TabletBridge.KIND_OUT) {
                    lastPoseHadPressure = false
                }
                continue
            }
            if (closingMirroredPress || penSampleMirrorsToMouse(sample.tool, windowMoved, lastPoseHadPressure)) {
                awtMapper.dispatch(target, sample, scale)
            }
            if (sample.kind == TabletBridge.KIND_UP || sample.kind == TabletBridge.KIND_OUT) {
                lastPoseHadPressure = false
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

    private fun fingerIsOnPassthrough(target: Component, sample: TabletPenDecoder.DecodedSample): Boolean {
        val origin = runCatching { target.locationOnScreen }.getOrNull() ?: return false
        val screenX = origin.x + sample.x.toInt()
        val screenY = origin.y + sample.y.toInt()
        val onControl = DesktopTouchDragExclusion.containsOverlay(window, screenX, screenY)
        if (onControl) println("TABLET: finger placed on a control at screen=($screenX,$screenY)")
        return onControl
    }

    private val controlLatch = FingerControlLatch()

    /** Fingers as real Compose touch, when that is switched on and this Compose build allows it. */
    private val composeTouch: ComposeTouchInjector? by lazy {
        if (ComposeTouchInjector.enabled) ComposeTouchInjector.bindOrNull(window) else null
    }

    private fun dispatchFinger(target: Component, sample: TabletPenDecoder.DecodedSample, windowMoved: Boolean) {
        val touch = composeTouch
        if (touch != null) {
            if (windowMoved) touch.releaseAll() else touch.onSample(target, sample)
            return
        }
        if (sample.kind == TabletBridge.KIND_CANCEL) {
            boardTouch.deliver(WindowPenTarget(window), sample.toBoardTouch())
            controlLatch.clear()
            // An Ink cancel is not this finger. Cancelling the pointer here kills a paused scroll.
            if (sample.tool == TabletBridge.TOOL_TOUCH) fingerPointer.cancel()
            return
        }
        val lifting = sample.kind == TabletBridge.KIND_UP || sample.kind == TabletBridge.KIND_OUT
        if (!windowMoved || lifting) {
            when (val offer = boardTouch.deliver(WindowPenTarget(window), sample.toBoardTouch())) {
                CanvasBoardTouchResult.Ignored -> {
                    val onControl = controlLatch.owns(sample.kind) { fingerIsOnPassthrough(target, sample) }
                    if (onControl) {
                        fingerPointer.onControlSample(target, sample)
                    } else if (!windowMoved) {
                        fingerPointer.onSample(target, sample)
                    }
                }
                CanvasBoardTouchResult.Consumed -> {
                    if (lifting) controlLatch.clear()
                    // A second finger riding along must not let go of the one holding the mouse.
                    fingerPointer.cancelScroll()
                    if (lifting && !fingerPointer.isHolding) {
                        fingerPointer.exitPointer(target, Point(sample.x.toInt(), sample.y.toInt()))
                    }
                    noteBoardPinch(sample)
                }
                is CanvasBoardTouchResult.Tap -> {
                    controlLatch.clear()
                    fingerPointer.cancel()
                    fingerPointer.tapAt(target, offer.x.toInt(), offer.y.toInt())
                }
                // One finger on the board is the mouse, so the current tool draws or selects.
                is CanvasBoardTouchResult.Press -> {
                    controlLatch.clear()
                    fingerPointer.hold(
                        target,
                        Point(offer.x.toInt(), offer.y.toInt()),
                        Point(offer.toX.toInt(), offer.toY.toInt()),
                    )
                }
                is CanvasBoardTouchResult.Drag -> fingerPointer.dragHeld(Point(offer.x.toInt(), offer.y.toInt()))
                CanvasBoardTouchResult.Release -> {
                    controlLatch.clear()
                    fingerPointer.releaseControl()
                }
            }
        }
    }

    private val touchContacts = mutableSetOf<Int>()
    private var pinchLogged = false
    private var fingerSource: String? = null
    private var awaitPenStroke = false

    private fun noteTouchContact(sample: TabletPenDecoder.DecodedSample, scale: Double) {
        if (sample.tool != TabletBridge.TOOL_TOUCH) return
        when (sample.kind) {
            TabletBridge.KIND_DOWN -> {
                touchContacts.add(sample.contact)
                println(
                    "TABLET: finger down contact=${sample.contact} fingersDown=${touchContacts.size} at=(${sample.x},${sample.y}) scale=$scale",
                )
            }
            TabletBridge.KIND_UP, TabletBridge.KIND_OUT, TabletBridge.KIND_CANCEL -> {
                touchContacts.remove(sample.contact)
                if (touchContacts.size < 2) pinchLogged = false
            }
        }
    }

    /** Once per gesture, the first move the board consumed while two fingers were down. */
    private fun noteBoardPinch(sample: TabletPenDecoder.DecodedSample) {
        if (pinchLogged || sample.kind != TabletBridge.KIND_MOVE || touchContacts.size < 2) return
        pinchLogged = true
        println("TABLET: board pinch contacts=${touchContacts.sorted().joinToString(",")}")
    }

    private fun windowTitle(): String = (window as? java.awt.Frame)?.title ?: window.name.orEmpty()

    private fun logPenStroke(target: Component, sample: TabletPenDecoder.DecodedSample, scale: Double, taken: Boolean) {
        val pointer = java.awt.MouseInfo.getPointerInfo()?.location
        val origin = runCatching { target.locationOnScreen }.getOrNull()
        val cursorX = if (pointer != null && origin != null) (pointer.x - origin.x) / scale else Double.NaN
        val cursorY = if (pointer != null && origin != null) (pointer.y - origin.y) / scale else Double.NaN
        val toolName = if (sample.tool == TabletBridge.TOOL_ERASER) "eraser" else "draw"
        val canvas = if (taken) "taken" else "declined"
        println(
            "TABLET: pen stroke tool=$toolName pressure=${sample.force} at=(${sample.x},${sample.y}) cursor=($cursorX,$cursorY) canvas=$canvas",
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 8L
    }
}

private fun TabletPenDecoder.DecodedSample.toBoardTouch(): CanvasBoardTouchSample {
    val phase = when (kind) {
        TabletBridge.KIND_DOWN -> CanvasBoardTouchSample.Phase.DOWN
        TabletBridge.KIND_MOVE -> CanvasBoardTouchSample.Phase.MOVE
        TabletBridge.KIND_CANCEL -> CanvasBoardTouchSample.Phase.CANCEL
        else -> CanvasBoardTouchSample.Phase.UP
    }
    return CanvasBoardTouchSample(contact = contact, phase = phase, x = x, y = y)
}

internal data class WindowPenTarget(val window: Window) : CanvasPenTarget

/**
 * Whether a tablet sample should also be posted as a mouse event.
 *
 * A real pen still needs that path: AWT has no stylus, so buttons and text would ignore it.
 * A finger and the system mouse have no pressure axis; Windows Ink reports them through the
 * same bridge, and posting them again is a second pointer — the arrow cursor. A sample that
 * arrives while the window is moving is that pointer sliding across the page under the drag.
 */
/** A finger claim sticks for the session. A pressureless Ink sample must not clear it. */
/**
 * Whether a finger gesture belongs to a published control.
 *
 * Decided where the finger is first placed. The touch-down sample is the previous
 * pose, often nowhere near the finger, so deciding there sent a slider drag to the
 * scroll path. A later sample that slides onto the panel does not stop a scroll
 * that started on the list.
 */
internal class FingerControlLatch {
    private var onControl: Boolean? = null
    private var placing = false

    fun owns(kind: Int, fingerOnControl: () -> Boolean): Boolean {
        when (kind) {
            TabletBridge.KIND_DOWN -> {
                onControl = null
                placing = true
            }
            TabletBridge.KIND_MOVE -> if (placing) {
                placing = false
                onControl = fingerOnControl()
            }
            TabletBridge.KIND_UP, TabletBridge.KIND_OUT, TabletBridge.KIND_CANCEL -> {
                val owned = onControl == true
                clear()
                return owned
            }
        }
        return onControl == true
    }

    fun clear() {
        onControl = null
        placing = false
    }
}

internal fun keepsFingerOwnership(tool: Int, kind: Int, alreadyOwned: Boolean): Boolean {
    if (tool == TabletBridge.TOOL_TOUCH || kind == TabletBridge.KIND_CANCEL) return true
    return alreadyOwned
}

internal fun isPenNib(tool: Int): Boolean =
    tool == TabletBridge.TOOL_DRAW || tool == TabletBridge.TOOL_ERASER

/** A pen down with no pose yet must wait. Emitting it starts a stroke at the origin, or drops it. */
internal fun penDownWaitsForPose(tool: Int, kind: Int, force: Float): Boolean =
    isPenNib(tool) && kind == TabletBridge.KIND_DOWN && force == TabletBridge.NO_PRESSURE

internal fun penSampleMirrorsToMouse(tool: Int, windowMoved: Boolean, hasPressureAxis: Boolean): Boolean =
    isPenNib(tool) && hasPressureAxis && !windowMoved
