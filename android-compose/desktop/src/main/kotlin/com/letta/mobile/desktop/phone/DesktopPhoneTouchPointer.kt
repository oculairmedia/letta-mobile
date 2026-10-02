package com.letta.mobile.desktop.phone

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerType
import com.letta.mobile.desktop.input.TabletBridge
import com.letta.mobile.desktop.input.TabletPenDecoder
import com.letta.mobile.desktop.touch.ComposeTouchInjector
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Makes the left mouse button arrive in Compose as a finger, so the phone preview exercises the
 * Touch code paths a mouse never reaches: one-finger canvas panning (CanvasBoardGestures branches on
 * [PointerType.Touch]), drag-to-scroll lists (Foundation refuses to drag-scroll a mouse), and touch
 * text selection. Taps, long presses and drags (the bar's swipe up, the chat head's drag and snap)
 * work either way, since their detectors do not look at the pointer type.
 *
 * The finger is the app's own: [ComposeTouchInjector], which hands Windows fingers to the window's
 * scene, is fed the mouse as one contact. A pushed [EventQueue], which sees events before Compose's
 * own listeners, turns left-button press/drag/release into its down/move/up samples and keeps them
 * from Compose as a mouse. Hover (moves without a button, enter, exit) is dropped, since a phone has
 * none; right and middle buttons and the wheel pass through as a mouse.
 *
 * If the scene cannot be reached (a Compose upgrade moved it), [install] returns false and the
 * preview keeps working with an ordinary mouse.
 */
internal object DesktopPhoneTouchPointer {
    /** Installs the translation for [window] while [enabled] says so. False (logged) when the scene is unavailable. */
    fun install(window: ComposeWindow, enabled: () -> Boolean): Boolean {
        val injector = ComposeTouchInjector.bindOrNull(window)
        if (injector == null) {
            println("PHONE: mouse-as-touch unavailable (no Compose scene); the mouse stays a mouse.")
            return false
        }
        EventQueue.invokeLater {
            Toolkit.getDefaultToolkit().systemEventQueue.push(TouchQueue(window, injector, enabled))
        }
        return true
    }

    private class TouchQueue(
        private val window: ComposeWindow,
        private val injector: ComposeTouchInjector,
        private val enabled: () -> Boolean,
    ) : EventQueue() {
        /** A left-button gesture is in flight as touch; it stays touch until release, whatever [enabled] says. */
        private var touchDown = false

        override fun dispatchEvent(event: AWTEvent) {
            val handled = windowMouseEvent(event)?.let(::translate) ?: false
            if (!handled) super.dispatchEvent(event)
        }

        /** [event] as a mouse event in the phone's window, or null for anything else. */
        private fun windowMouseEvent(event: AWTEvent): MouseEvent? =
            (event as? MouseEvent)?.takeIf { SwingUtilities.getWindowAncestor(it.component) === window }

        /** True when [event] was handled here (sent as touch, or dropped as hover). */
        private fun translate(event: MouseEvent): Boolean {
            if (!active()) return false
            return when (event.id) {
                MouseEvent.MOUSE_PRESSED -> press(event)
                MouseEvent.MOUSE_DRAGGED -> drag(event)
                MouseEvent.MOUSE_RELEASED -> release(event)
                // A phone has no hover, and the click AWT synthesises after a release is not an input.
                MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED -> !touchDown
                MouseEvent.MOUSE_CLICKED -> SwingUtilities.isLeftMouseButton(event)
                else -> false
            }
        }

        /** Translating, or finishing a touch gesture that started while it was. */
        private fun active(): Boolean = touchDown || enabled()

        private fun press(event: MouseEvent): Boolean {
            if (!SwingUtilities.isLeftMouseButton(event)) return false
            if (!event.component.isFocusOwner) event.component.requestFocus()
            // The injector places a finger on its first move after the down, as the pen bridge reports it.
            sample(event, TabletBridge.KIND_DOWN)
            sample(event, TabletBridge.KIND_MOVE)
            touchDown = true
            return true
        }

        private fun drag(event: MouseEvent): Boolean {
            if (!touchDown) return false
            sample(event, TabletBridge.KIND_MOVE)
            return true
        }

        private fun release(event: MouseEvent): Boolean {
            if (!touchDown) return false
            sample(event, TabletBridge.KIND_UP)
            touchDown = false
            return true
        }

        private fun sample(event: MouseEvent, kind: Int) {
            val x = event.x.toFloat()
            val y = event.y.toFloat()
            injector.onSample(
                event.component,
                TabletPenDecoder.DecodedSample(kind = kind, x = x, y = y, rawX = x, rawY = y, force = 1f, tool = FINGER_TOOL, contact = MOUSE_CONTACT),
            )
        }
    }

    /** The one contact the mouse plays. */
    private const val MOUSE_CONTACT = 1

    /** The tool field is the pen's; the injector does not read it for fingers. */
    private const val FINGER_TOOL = 0
}
