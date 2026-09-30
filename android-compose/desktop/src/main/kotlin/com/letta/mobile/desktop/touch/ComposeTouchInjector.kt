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
 * The scene is not public. It is reached by reflection from the window; when a Compose upgrade
 * moves it, [bindOrNull] logs and returns null, and `ComposeTouchInjectorTest` fails first.
 *
 * A finger is touch to the rest of the app too: each press is recorded in [DesktopTouchOrigin],
 * so a text field it focuses raises the touch keyboard, and a tap on a field that already has
 * focus raises it through [DesktopTouchKeyboardTaps].
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

    /** Where and when each finger was placed, to tell a tap from a drag when it lifts. */
    private val placedAt = HashMap<Int, Pair<Offset, Long>>()

    fun onSample(target: Component, sample: TabletPenDecoder.DecodedSample) {
        val contact = sample.contact
        when (sample.kind) {
            TabletBridge.KIND_DOWN -> placing += contact
            TabletBridge.KIND_MOVE -> {
                val position = toScene(target, sample.x, sample.y) ?: return
                if (placing.remove(contact)) {
                    active[contact] = position
                    val now = System.currentTimeMillis()
                    placedAt[contact] = position to now
                    DesktopTouchOrigin.record(isTouch = true, atMillis = now)
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
                val lifted = active.remove(contact)
                val placed = placedAt.remove(contact)
                if (lifted != null && placed != null && isTap(placed, lifted)) {
                    SwingUtilities.invokeLater { DesktopTouchKeyboardTaps.gate?.fingerTapped() }
                }
            }
            // An Ink cancel is the Ink copy of a finger leaving, not this finger.
            TabletBridge.KIND_CANCEL -> Unit
        }
    }

    private fun isTap(placed: Pair<Offset, Long>, lifted: Offset): Boolean {
        val density = content.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.takeIf { it > 0f } ?: 1f
        return (lifted - placed.first).getDistance() <= TAP_SLOP_DP * density &&
            System.currentTimeMillis() - placed.second <= TAP_MILLIS
    }

    /** Lets go of every finger, as when the window moves under them. */
    fun releaseAll() {
        placing.clear()
        placedAt.clear()
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
        private const val TAP_SLOP_DP = 12f
        private const val TAP_MILLIS = 300L

        /** Reaches [window]'s Compose scene, or null (logged) when this Compose build is laid out differently. */
        fun bindOrNull(window: Window): ComposeTouchInjector? = runCatching {
            val panel = field(window, PANEL_FIELD)
            val composeContainer = field(panel, CONTAINER_FIELD)
            val mediator = field(composeContainer, MEDIATOR_FIELD)
            val mediatorClass = mediator.javaClass
            val scene = mediatorClass.getMethod(GET_SCENE, mediatorClass).invoke(null, mediator) as ComposeScene
            val container = mediatorClass.getMethod(GET_CONTAINER, mediatorClass).invoke(null, mediator) as JComponent
            val content = mediatorClass.getMethod(GET_CONTENT).invoke(mediator) as JComponent
            val bounds = mediatorClass.getMethod(GET_BOUNDS)
            ComposeTouchInjector(scene, container, content) {
                (bounds.invoke(mediator) as? androidx.compose.ui.geometry.Rect)?.topLeft ?: Offset.Zero
            }
        }.onSuccess {
            // The same fingers still reach AWT; its copy must not land as a second pointer.
            DesktopTouchEchoFilter.guard(window)
            println("TOUCH: fingers go to Compose as touch")
        }
            .onFailure { println("TOUCH: compose scene unavailable; fingers do nothing until it is found again: $it") }
            .getOrNull()

        private const val PANEL_FIELD = "composePanel"
        private const val CONTAINER_FIELD = "_composeContainer"
        private const val MEDIATOR_FIELD = "mediator"
        private const val GET_SCENE = "access\$getScene"
        private const val GET_CONTAINER = "access\$getContainer\$p"
        private const val GET_CONTENT = "getContentComponent"
        private const val GET_BOUNDS = "getSceneBoundsInPx"

        /**
         * The steps from a window to its scene that this Compose build no longer has, checked on
         * the classes alone so it needs no window. Empty when [bindOrNull] can reach the scene.
         */
        internal fun missingSceneSteps(): List<String> {
            fun declares(type: Class<*>?, name: String): Boolean =
                generateSequence(type) { it.superclass }.any { t -> t.declaredFields.any { it.name == name } }
            val missing = mutableListOf<String>()
            val window = Class.forName("androidx.compose.ui.awt.ComposeWindow")
            val panel = Class.forName("androidx.compose.ui.awt.ComposeWindowPanel")
            val container = Class.forName("androidx.compose.ui.scene.ComposeContainer")
            val mediator = Class.forName("androidx.compose.ui.scene.ComposeSceneMediator")
            if (!declares(window, PANEL_FIELD)) missing += "ComposeWindow.$PANEL_FIELD"
            if (!declares(panel, CONTAINER_FIELD)) missing += "ComposeWindowPanel.$CONTAINER_FIELD"
            if (!declares(container, MEDIATOR_FIELD)) missing += "ComposeContainer.$MEDIATOR_FIELD"
            listOf(GET_SCENE, GET_CONTAINER, GET_CONTENT, GET_BOUNDS).forEach { name ->
                if (mediator.methods.none { it.name == name }) missing += "ComposeSceneMediator.$name"
            }
            return missing
        }

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
