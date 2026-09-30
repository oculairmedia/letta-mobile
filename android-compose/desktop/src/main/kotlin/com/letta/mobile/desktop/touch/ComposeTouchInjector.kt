package com.letta.mobile.desktop.touch

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import com.letta.mobile.desktop.input.TabletBridge
import com.letta.mobile.desktop.input.TabletPenDecoder
import java.awt.Component
import java.awt.Point
import java.awt.Window
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Hands desktop fingers to Compose as real touch pointers.
 *
 * Compose Desktop sends every AWT event into its scene as a mouse, so a finger there
 * scrolls with synthetic wheel steps, drags with a mouse cursor, and never gets the touch
 * text handles. The pointer hook already knows each finger by id; this sends them into the
 * window's scene typed [PointerType.Touch], so Compose's own slop, fling, sliders, text
 * selection and multi-finger gestures run as they do on a phone.
 *
 * The scene is not public. It is reached by reflection from the window, and every step
 * fails soft: [bindOrNull] returns null and the caller keeps the older finger path, which
 * `LETTA_TOUCH_COMPOSE=0` (or `-Dletta.touch.compose=false`) also selects.
 */
@OptIn(InternalComposeUiApi::class)
internal class ComposeTouchInjector private constructor(
    private val scene: ComposeScene,
    private val container: JComponent,
    private val content: JComponent,
    private val sceneTopLeft: () -> Offset,
) {
    /** Fingers Compose has been told are down, by contact, at their scene position in pixels. */
    private val active = LinkedHashMap<Int, Offset>()

    /** Fingers that are down but not yet placed: the down sample is the previous pose. */
    private val placing = mutableSetOf<Int>()

    fun onSample(target: Component, sample: TabletPenDecoder.DecodedSample) {
        val contact = sample.contact
        when (sample.kind) {
            TabletBridge.KIND_DOWN -> placing += contact
            TabletBridge.KIND_MOVE -> {
                val position = toScene(target, sample.x, sample.y) ?: return
                if (placing.remove(contact)) {
                    active[contact] = position
                    send(PointerEventType.Press, released = null)
                } else if (contact in active) {
                    if (active[contact] == position) return
                    active[contact] = position
                    send(PointerEventType.Move, released = null)
                }
            }
            TabletBridge.KIND_UP, TabletBridge.KIND_OUT -> {
                placing -= contact
                if (contact !in active) return
                send(PointerEventType.Release, released = contact)
                active -= contact
            }
            // An Ink cancel is the Ink copy of a finger leaving, not this finger.
            TabletBridge.KIND_CANCEL -> Unit
        }
    }

    /** Lets go of every finger, as when the window moves under them. */
    fun releaseAll() {
        placing.clear()
        while (active.isNotEmpty()) {
            val contact = active.keys.first()
            send(PointerEventType.Release, released = contact)
            active -= contact
        }
    }

    private fun send(type: PointerEventType, released: Int?) {
        val pointers = active.map { (contact, position) ->
            ComposeScenePointer(
                id = PointerId(contact.toLong()),
                position = position,
                pressed = contact != released,
                type = PointerType.Touch,
            )
        }
        runCatching { scene.sendPointerEvent(eventType = type, pointers = pointers, timeMillis = System.currentTimeMillis()) }
            .onFailure { println("TOUCH: compose send failed: $it") }
    }

    /**
     * The same arithmetic Compose applies to a mouse event: the point in the scene container,
     * as dp, times the content's density, less the scene's top-left.
     */
    private fun toScene(target: Component, x: Float, y: Float): Offset? {
        if (!target.isShowing || !container.isShowing) return null
        val origin = SwingUtilities.convertPoint(target, Point(0, 0), container)
        val density = content.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.takeIf { it > 0f } ?: 1f
        return Offset((origin.x + x) * density, (origin.y + y) * density) - sceneTopLeft()
    }

    companion object {
        val enabled: Boolean =
            System.getProperty("letta.touch.compose")?.toBoolean() ?: (System.getenv("LETTA_TOUCH_COMPOSE") != "0")

        /** Reaches [window]'s Compose scene, or null (logged) when this Compose build is laid out differently. */
        fun bindOrNull(window: Window): ComposeTouchInjector? = runCatching {
            val panel = field(window, "composePanel")
            val composeContainer = field(panel, "_composeContainer")
            val mediator = field(composeContainer, "mediator")
            val mediatorClass = mediator.javaClass
            val scene = mediatorClass.getMethod("access\$getScene", mediatorClass).invoke(null, mediator) as ComposeScene
            val container = mediatorClass.getMethod("access\$getContainer\$p", mediatorClass).invoke(null, mediator) as JComponent
            val content = mediatorClass.getMethod("getContentComponent").invoke(mediator) as JComponent
            val bounds = mediatorClass.getMethod("getSceneBoundsInPx")
            ComposeTouchInjector(scene, container, content) {
                (bounds.invoke(mediator) as? androidx.compose.ui.geometry.Rect)?.topLeft ?: Offset.Zero
            }
        }.onSuccess { println("TOUCH: fingers go to Compose as touch") }
            .onFailure { println("TOUCH: compose scene unavailable, keeping the older finger path: $it") }
            .getOrNull()

        @Suppress("NoAnyType") // Walks Compose's private fields by reflection; their types are not public.
        private fun field(owner: Any, name: String): Any {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val found = type.declaredFields.firstOrNull { it.name == name }
                if (found != null) {
                    found.isAccessible = true
                    return requireNotNull(found.get(owner)) { "$name is null on ${owner.javaClass.name}" }
                }
                type = type.superclass
            }
            error("no field $name on ${owner.javaClass.name}")
        }
    }
}
