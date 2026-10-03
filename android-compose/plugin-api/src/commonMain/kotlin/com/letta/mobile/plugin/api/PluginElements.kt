package com.letta.mobile.plugin.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/** Something that happened to one of the plugin's own elements (wire notification `element.event`). */
@Serializable
public data class ElementEvent(
    /** The element's kind (a key of the manifest's `elements`). */
    public val kind: String,
    public val elementId: String,
    public val event: ElementEventType,
    /** The element's frame after the event, when it has one. */
    public val frame: ElementFrame? = null,
    public val canvasId: String? = null,
)

/** What happened to an element. */
@Serializable
public enum class ElementEventType {
    @SerialName("moved") MOVED,
    @SerialName("removed") REMOVED,
    @SerialName("focused") FOCUSED,
    @SerialName("viewOpened") VIEW_OPENED,
    @SerialName("viewClosed") VIEW_CLOSED,
}

/**
 * Which elements [PluginHost.readElements] answers: on [canvasId] (every board the plugin is on
 * when null), with one of [elementIds] (any when empty), of one of the plugin's [kinds] (any when
 * empty), and only the plugin's own when [ownOnly].
 */
@Serializable
public data class ElementQuery(
    public val canvasId: String? = null,
    public val elementIds: List<String> = emptyList(),
    public val kinds: List<String> = emptyList(),
    public val ownOnly: Boolean = true,
)

/**
 * An element as a plugin may see it: its [id], [type] (`ext:<pluginId>/<kind>` or a core type),
 * schema version [v], [frame] and board. [props], [ref] and [fallback] are filled in only for the
 * plugin's own elements.
 */
@Serializable
public data class PluginElementView(
    public val id: String,
    public val type: String,
    public val canvasId: String,
    public val v: Int? = null,
    public val frame: ElementFrame? = null,
    public val props: JsonObject? = null,
    public val ref: String? = null,
    public val fallback: ElementFallback? = null,
)

/** Who the plugin is, answered from [CanvasPlugin.initialize] (wire: `plugin.initialize` result's `pluginInfo`). */
@Serializable
public data class PluginInfo(
    /** The plugin's own build version, when it differs from the manifest's (diagnostics only). */
    public val build: String? = null,
    /** Free-form facts for the host's diagnostics (versions of what the plugin talks to); never a secret. */
    public val details: Map<String, String> = emptyMap(),
)

/**
 * Whether a plugin can do its work (wire `plugin.health` result `{status, reason?}`): [Ok],
 * [Degraded] (works, with a [Degraded.reason] the settings UI shows) or [Failed].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
public sealed interface PluginHealth {
    @Serializable
    @SerialName("ok")
    public data object Ok : PluginHealth

    @Serializable
    @SerialName("degraded")
    public data class Degraded(public val reason: String) : PluginHealth

    @Serializable
    @SerialName("failed")
    public data class Failed(public val reason: String) : PluginHealth
}
