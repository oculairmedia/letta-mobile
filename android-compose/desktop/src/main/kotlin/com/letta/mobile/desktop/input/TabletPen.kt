package com.letta.mobile.desktop.input

import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Point
import java.awt.Window
import com.letta.mobile.ui.canvas.CanvasPenEvent
import com.letta.mobile.ui.canvas.CanvasPenInput
import com.letta.mobile.ui.canvas.CanvasPenTool
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Drives the pen into the application as ordinary input.
 *
 * The pen has to do everything a mouse does — press a button, pick a note, drag a handle — not
 * only draw, so its events are posted as AWT mouse events on the window they belong to. Compose
 * then routes them through its own hit testing, and every control works with the pen without
 * knowing a pen exists. The alternative, feeding strokes straight to the canvas, would have given
 * us a pen that draws beautifully and cannot press "Select".
 *
 * What that path cannot carry is pressure: an AWT mouse event has none, and Compose reports 1.0.
 * So pressure is published separately in [pressure], for the drawing code to read at the moment it
 * builds a stroke.
 */
internal class TabletPen(
    private val window: Window,
    private val pollInterval: Long = POLL_INTERVAL_MS,
) {
    private val handles = mutableListOf<Triple<String, Long, Component>>()
    private var sawFrom: String? = null
    private var reportedGeometry = false
    private var down = false
    private var lastPoint = Point(0, 0)

    private val _pressure = MutableStateFlow(TabletBridge.NO_PRESSURE)

    /** The nib's current force, or [TabletBridge.NO_PRESSURE] when the tool has no pressure axis. */
    val pressure: StateFlow<Float> = _pressure

    /** True when a tablet is present and its events are being delivered. */
    val connected: Boolean get() = handles.isNotEmpty()

    fun start(scope: CoroutineScope): Boolean {
        // Every step says what happened. A pen bridge that fails silently is indistinguishable
        // from a pen that is not there, which is exactly how long this took to find.
        val load = runCatching { TabletBridge.available }
        if (load.getOrDefault(false) != true) {
            println("TABLET: native library did not load: ${load.exceptionOrNull()}")
            return false
        }

        // Windows Ink is COM, and COM is apartment-threaded: the manager must be created AND
        // pumped on one thread. It is also attached to a window, and Skiko draws into a
        // heavyweight child with its own handle — pen input goes to the window that owns the
        // pixels, not to the frame around it. Both of those are why the first attempt opened
        // cleanly and then heard nothing.
        val targets = nativeTargets()
        targets.forEach { (component, address) ->
            println("TABLET: candidate ${component::class.java.name} handle=$address")
        }
        if (targets.isEmpty()) {
            println("TABLET: no native handle anywhere")
            return false
        }

        scope.launch(Dispatchers.Main) {
            // Open against every window that has a handle. Windows Ink delivers to exactly one of
            // them and there is no way to ask which from here, so we listen on both and say which
            // one spoke. The standalone probe (native/tablet_input/examples/probe.rs) proves this
            // tablet does report; the only question left is where.
            targets.forEach { (component, address) ->
                val opened = runCatching { TabletBridge.nativeOpen(address) }
                    .onFailure { println("TABLET: nativeOpen threw for ${component.label()}: $it") }
                    .getOrDefault(0L)
                if (opened != 0L) {
                    handles += Triple(component.label(), opened, component)
                    println("TABLET: listening on ${component.label()} ($address)")
                } else {
                    println("TABLET: ${component.label()} has no tablet service")
                }
            }
            if (handles.isEmpty()) return@launch
            println("TABLET: polling on ${Thread.currentThread().name}")
            try {
                while (isActive && handles.isNotEmpty()) {
                    dropDeadTargets()
                    if (handles.isEmpty()) break
                    handles.toList().forEach { (name, open, component) ->
                        val events = runCatching { TabletBridge.nativePoll(open) }
                            .onFailure { println("TABLET: poll threw: $it") }
                            .getOrDefault(FloatArray(0))
                        if (events.isNotEmpty()) {
                            if (sawFrom == null) {
                                sawFrom = name
                                println("TABLET: events are coming from $name: ${events.take(16).joinToString()}")
                            }
                            // Posting into a component that is no longer on screen drives a layout
                            // pass on a Compose scene that has been disposed, which throws on the
                            // event thread and takes the whole app down. Drop the frame instead:
                            // the pen has nowhere to draw anyway.
                            if (sawFrom == name && component.isShowing) dispatch(events, component)
                        }
                    }
                    delay(pollInterval)
                }
            } finally {
                stop()
            }
        }
        return true
    }

    /**
     * Every component from the window down that owns a native window handle, outermost first.
     *
     * A Swing hierarchy is mostly lightweight — only a few components have a handle of their own —
     * and the pen belongs to the innermost one, which is where the rendering surface lives.
     */
    private fun nativeTargets(): List<Pair<Component, Long>> {
        val found = mutableListOf<Pair<Component, Long>>()
        fun visit(component: Component) {
            val address = runCatching { Native.getComponentPointer(component) }
                .getOrNull()
                ?.let { Pointer.nativeValue(it) }
                ?.takeIf { it != 0L }
            if (address != null) found += component to address
            if (component is java.awt.Container) component.components.forEach { visit(it) }
        }
        visit(window)
        return found
    }

    /**
     * Closes the handles whose component no longer has a native window behind it.
     *
     * The targets are found once, when the pen starts, and a Compose window can replace its
     * rendering layer underneath us. A handle onto a component that has been disposed is not just
     * useless - it is the one that crashes the app when the next event arrives.
     */
    private fun dropDeadTargets() {
        val dead = handles.filterNot { (_, _, component) -> component.isDisplayable }
        if (dead.isEmpty()) return
        handles.removeAll(dead)
        dead.forEach { (name, address, _) ->
            println("TABLET: $name is gone; closing its handle")
            runCatching { TabletBridge.nativeClose(address) }
        }
        if (handles.isEmpty()) sawFrom = null
    }

    /**
     * Says once, with numbers, where the pen thinks it is against where the pointer actually is.
     *
     * An offset cursor is the one pen fault that cannot be reasoned about from a stack: it needs
     * the raw physical point, the scale it was divided by, the component the event is posted to
     * and where that component sits on screen. Printing all four turns "it is off by a bit" into
     * a measurement.
     */
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

    fun stop() {
        val open = handles.toList()
        handles.clear()
        open.forEach { (_, address, _) -> runCatching { TabletBridge.nativeClose(address) } }
    }

    /**
     * Turns one drained frame into AWT events on [target], the component Windows Ink is reporting
     * against.
     *
     * Two conversions matter. The positions arrive in the target window's own coordinates, so they
     * are dispatched to that component rather than looked up from the frame. And they arrive in
     * PHYSICAL pixels while AWT works in logical ones, so on any scaled display an unconverted
     * point lands somewhere else entirely — which is what a pen that reports cleanly and draws
     * nothing looks like.
     */
    private fun dispatch(events: FloatArray, target: Component) {
        val scale = runCatching {
            target.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0.0 } ?: 1.0
        }.getOrDefault(1.0)
        var index = 0
        while (index + TabletBridge.STRIDE <= events.size) {
            val kind = events[index].toInt()
            val x = (events[index + 1] / scale).toFloat()
            val y = (events[index + 2] / scale).toFloat()
            val force = events[index + 3]
            val tool = events[index + 4].toInt()
            index += TabletBridge.STRIDE
            if (force != TabletBridge.NO_PRESSURE) _pressure.value = force

            // The canvas gets first refusal: a stroke carries pressure per sample, which the mouse
            // path below cannot express. What it takes, it keeps — delivering the same event twice
            // would draw the stroke a second time without pressure.
            if (offerToCanvas(kind, x, y, force, tool)) continue

            val point = Point(x.toInt(), y.toInt())
            reportGeometryOnce(target, events[index - TabletBridge.STRIDE + 1], events[index - TabletBridge.STRIDE + 2], scale, point)
            when (kind) {
                TabletBridge.KIND_DOWN -> {
                    down = true
                    lastPoint = point
                    post(target, point, MouseEvent.MOUSE_PRESSED)
                }
                TabletBridge.KIND_UP -> {
                    // Cleared BEFORE the release is posted: AWT's extended modifiers describe the
                    // button state AFTER the event, so releasing while [down] is still true tells
                    // everything downstream that button 1 is held, and the pointer stays stuck in
                    // a drag it never leaves.
                    down = false
                    post(target, point, MouseEvent.MOUSE_RELEASED)
                    // A tap is a press, a release and a click: without the click, buttons that
                    // listen for one do nothing and the pen appears to select nothing.
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
    }

    /** True when the canvas took this event and it must not also become a mouse event. */
    private fun offerToCanvas(kind: Int, x: Float, y: Float, force: Float, tool: Int): Boolean {
        val consumer = CanvasPenInput.consumer ?: return false
        val phase = when (kind) {
            TabletBridge.KIND_DOWN -> CanvasPenEvent.Phase.DOWN
            TabletBridge.KIND_UP -> CanvasPenEvent.Phase.UP
            TabletBridge.KIND_MOVE -> CanvasPenEvent.Phase.MOVE
            TabletBridge.KIND_IN -> CanvasPenEvent.Phase.IN
            TabletBridge.KIND_OUT -> CanvasPenEvent.Phase.OUT
            else -> return false
        }
        val penTool = when (tool) {
            TabletBridge.TOOL_DRAW -> CanvasPenTool.DRAW
            TabletBridge.TOOL_ERASER -> CanvasPenTool.ERASER
            else -> CanvasPenTool.OTHER
        }
        val taken = runCatching {
            consumer(
                CanvasPenEvent(
                    phase = phase,
                    x = x,
                    y = y,
                    pressure = force.takeIf { it != TabletBridge.NO_PRESSURE },
                    tool = penTool,
                ),
            )
        }.getOrDefault(false)
        // A stroke the canvas is drawing must not also arrive as a drag, so the press state is
        // kept in step even for events we hand over.
        if (taken) {
            when (kind) {
                TabletBridge.KIND_DOWN -> down = true
                TabletBridge.KIND_UP, TabletBridge.KIND_OUT -> down = false
            }
        }
        return taken
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

    /** Anonymous Skiko layers have no simple name; fall back to something a log can print. */
    private fun Component.label(): String =
        this::class.java.simpleName.ifEmpty { this::class.java.name.substringAfterLast('.') }

    private fun Point.near(other: Point): Boolean =
        kotlin.math.abs(x - other.x) <= TAP_SLOP && kotlin.math.abs(y - other.y) <= TAP_SLOP

    private companion object {
        /** ~120Hz: fast enough that a stroke does not look segmented, cheap enough to idle at. */
        const val POLL_INTERVAL_MS = 8L

        /** How far the pen may travel between press and release and still count as a tap. */
        const val TAP_SLOP = 4
    }
}
