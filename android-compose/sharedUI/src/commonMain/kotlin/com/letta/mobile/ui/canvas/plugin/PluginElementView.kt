package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What a renderer is handed for one plugin element on the board (letta-mobile-s416w.4): the element
 * as the board holds it, what this client knows about its plugin, its snapshot as far as it has
 * arrived, and the generic status the card may show.
 *
 * A renderer draws from this and nothing else; it never reaches into the scene or the session.
 */
@Immutable
data class PluginElementView(
    val element: CanvasPluginElement,
    val availability: PluginAvailability = PluginAvailability.Unknown,
    val snapshot: PluginSnapshotImage = PluginSnapshotImage.None,
    /** True once a registered renderer gave up on this element; the board then draws the fallback card. */
    val faulted: Boolean = false,
    /** The canvas the element is on: a live renderer opens the element's view for it. Null off a board. */
    val canvasId: String? = null,
) {
    /** The props the fallback card may read: `status` and `progress`, nothing else. */
    val status: PluginElementStatus? = PluginElementStatus.of(element.props)

    /** The title to show, never blank. */
    val title: String? get() = element.fallback.title.trim().takeIf { it.isNotEmpty() }
}

/**
 * What the board knows about the plugin that owns an element. [install] comes from the host's
 * catalog of plugins; [offline] says the plugin's host cannot be reached right now.
 */
@Immutable
data class PluginAvailability(
    val install: PluginInstallState = PluginInstallState.UNKNOWN,
    val offline: Boolean = false,
) {
    companion object {
        /** Nothing known: no badges. A client without a plugin catalog shows every element so. */
        val Unknown: PluginAvailability = PluginAvailability()
    }
}

/** The plugin of an element as the host's catalog has it. */
enum class PluginInstallState {
    /** No catalog to ask (no badge). */
    UNKNOWN,

    /** The catalog has the plugin and its kind at this version or later. */
    INSTALLED,

    /** The catalog does not know the plugin. */
    NOT_INSTALLED,

    /** The element was written by a newer version of the kind than the one installed. */
    NEWER_VERSION,
}

/** Answers what the board knows about an element's plugin; shells provide one through [LocalPluginAvailability]. */
fun interface PluginAvailabilitySource {
    fun availabilityOf(element: CanvasPluginElement): PluginAvailability
}

/** The availability of each element's plugin; by default nothing is known and no badge is drawn. */
val LocalPluginAvailability = staticCompositionLocalOf { PluginAvailabilitySource { PluginAvailability.Unknown } }

/** An element's snapshot as far as this client has it. */
@Immutable
sealed interface PluginSnapshotImage {
    /** The element has no snapshot: the card draws its icon instead. */
    data object None : PluginSnapshotImage

    /** The snapshot is not here yet (neither in the asset store nor fetched): a skeleton. */
    data object Loading : PluginSnapshotImage

    /** The snapshot, decoded. */
    data class Ready(val bitmap: ImageBitmap) : PluginSnapshotImage

    /** Bytes arrived that are not an image this platform decodes: the icon, as with no snapshot. */
    data object Unreadable : PluginSnapshotImage
}

/**
 * The one generic convention the fallback card reads from an element's props (plan section 3.1's
 * example kind): `status` (`idle`, `running`, `done`, `failed`, or any other short word) and
 * `progress` (0 to 1). Anything else in the props is the plugin's own and is never looked at.
 */
@Immutable
data class PluginElementStatus(val status: String?, val progress: Float?) {
    /** How [status] reads: a known word gets its tone and a translated label. */
    val tone: PluginStatusTone get() = PluginStatusTone.of(status)

    companion object {
        private const val STATUS = "status"
        private const val PROGRESS = "progress"
        private const val MAX_STATUS = 32

        /** The status of [props], or null when they carry neither a status nor a progress. */
        fun of(props: JsonObject): PluginElementStatus? {
            val status = (props[STATUS] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(MAX_STATUS)?.takeIf { it.isNotEmpty() }
            val progress = (props[PROGRESS] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                ?.takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f)
            if (status == null && progress == null) return null
            return PluginElementStatus(status, progress)
        }
    }
}

/** How a status reads on the card. */
enum class PluginStatusTone {
    IDLE, RUNNING, DONE, FAILED, OTHER;

    companion object {
        fun of(status: String?): PluginStatusTone = when (status?.lowercase()) {
            "idle" -> IDLE
            "running" -> RUNNING
            "done" -> DONE
            "failed" -> FAILED
            else -> OTHER
        }
    }
}

/**
 * The board's part in an element's card, made once per element by the layer:
 * [moveHandle] goes on whatever the renderer offers as its handle bar (a drag there moves the
 * element), [open] follows the element's `fallback.openUrl` through the platform's link handler
 * (null when there is none, or it is not a link this client opens), and [fail] is how a live
 * renderer that cannot show the element hands it back to the fallback card.
 */
@Stable
class PluginElementChrome(
    val moveHandle: Modifier,
    val open: (() -> Unit)?,
    val fail: (reason: String) -> Unit,
)
