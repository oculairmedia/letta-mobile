@file:OptIn(InternalComposeUiApi::class)

package com.letta.mobile.desktop.phone

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.lang.reflect.Field
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Makes the left mouse button arrive in Compose as a finger, so the phone preview exercises the
 * Touch code paths a mouse never reaches: one-finger canvas panning (CanvasBoardGestures branches on
 * [PointerType.Touch]), drag-to-scroll lists (Foundation refuses to drag-scroll a mouse), and touch
 * text selection. Taps, long presses and drags (the bar's swipe up, the chat head's drag and snap)
 * work either way, since their detectors do not look at the pointer type.
 *
 * Compose Desktop stamps every AWT mouse event [PointerType.Mouse] inside its scene mediator, with
 * no public hook. This reaches the window's [ComposeScene] by reflection (ComposeWindow ->
 * ComposeWindowPanel -> ComposeContainer -> ComposeSceneMediator) and, from a pushed [EventQueue]
 * that sees events before Compose's own listeners, re-sends left-button press/drag/release as touch
 * events at the same position. Hover (moves without a button, enter, exit) is dropped, since a phone
 * has none; right and middle buttons and the wheel pass through as a mouse.
 *
 * Dev-only and best effort: if a Compose upgrade renames the fields, [install] logs why and returns
 * false, and the preview keeps working with an ordinary mouse.
 */
internal object DesktopPhoneTouchPointer {
    /** Installs the translation for [window] while [enabled] says so. False (logged) when the hook is unavailable. */
    fun install(window: ComposeWindow, enabled: () -> Boolean): Boolean {
        val target = runCatching { resolve(window) }.getOrElse { error ->
            println("PHONE: mouse-as-touch unavailable (${error.javaClass.simpleName}: ${error.message}); the mouse stays a mouse.")
            return false
        }
        EventQueue.invokeLater {
            Toolkit.getDefaultToolkit().systemEventQueue.push(TouchQueue(target, enabled))
        }
        return true
    }

    private class Target(val scene: ComposeScene, val content: Component, val container: JComponent)

    private fun resolve(window: ComposeWindow): Target {
        val panel = ComposeWindow::class.java.openField("composePanel").get(window)
        val container = panel.javaClass.openField("_composeContainer").get(panel)
        val mediator = container.javaClass.openField("mediator").get(container)
        val scene = (mediator.javaClass.openField("scene\$delegate").get(mediator) as Lazy<*>).value as ComposeScene
        val content = mediator.javaClass.getMethod("getContentComponent").invoke(mediator) as Component
        val root = mediator.javaClass.openField("container").get(mediator) as JComponent
        return Target(scene, content, root)
    }

    /** [name], declared on this class, made readable. */
    private fun Class<*>.openField(name: String): Field = getDeclaredField(name).apply { isAccessible = true }

    private class TouchQueue(private val target: Target, private val enabled: () -> Boolean) : EventQueue() {
        /** A left-button gesture is in flight as touch; it stays touch until release, whatever [enabled] says. */
        private var touchDown = false

        override fun dispatchEvent(event: AWTEvent) {
            val handled = contentMouseEvent(event)?.let(::translate) ?: false
            if (!handled) super.dispatchEvent(event)
        }

        /** [event] as a mouse event on the Compose content, or null for anything else. */
        private fun contentMouseEvent(event: AWTEvent): MouseEvent? =
            (event as? MouseEvent)?.takeIf { it.component === target.content }

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
            if (!target.content.isFocusOwner) target.content.requestFocus()
            send(event, PointerEventType.Press, pressed = true)
            touchDown = true
            return true
        }

        private fun drag(event: MouseEvent): Boolean {
            if (!touchDown) return false
            send(event, PointerEventType.Move, pressed = true)
            return true
        }

        private fun release(event: MouseEvent): Boolean {
            if (!touchDown) return false
            send(event, PointerEventType.Release, pressed = false)
            touchDown = false
            return true
        }

        private fun send(event: MouseEvent, type: PointerEventType, pressed: Boolean) {
            val point = SwingUtilities.convertPoint(event.component, event.point, target.container)
            val scale = target.content.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
            target.scene.sendPointerEvent(
                eventType = type,
                position = Offset(point.x * scale, point.y * scale),
                timeMillis = event.`when`,
                type = PointerType.Touch,
                buttons = PointerButtons(isPrimaryPressed = pressed),
                nativeEvent = event,
                button = PointerButton.Primary,
            )
        }
    }
}
