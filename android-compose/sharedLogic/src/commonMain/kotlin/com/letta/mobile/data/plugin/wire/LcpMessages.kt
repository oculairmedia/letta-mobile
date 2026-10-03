package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The params and results of every LCP wire v1 method (plan section 5). Their JSON is the contract
// a non-Kotlin plugin implements; the golden transcripts hold one session of each.

/** Who the host is, told to the plugin at `plugin.initialize`. */
@Serializable
data class LcpHostInfo(val name: String, val version: String)

/** Who the plugin says it is, answered to `plugin.initialize`. */
@Serializable
data class LcpPluginInfo(val name: String, val version: String)

/**
 * `plugin.initialize`: the handshake. The host offers [contractVersions] (every version it speaks)
 * with [contractVersion] its best; the plugin answers with the version it chose from them, or with
 * [LcpErrorCode.CONTRACT_MISMATCH] when it speaks none. [settings] are resolved and validated;
 * secrets are never here (env for `process`, headers for `service`).
 */
@Serializable
data class InitializeParams(
    val contractVersion: Int,
    val contractVersions: List<Int>,
    val pluginId: String,
    val settings: JsonObject,
    val hostInfo: LcpHostInfo,
)

@Serializable
data class InitializeResult(val ok: Boolean, val contractVersion: Int, val pluginInfo: LcpPluginInfo)

@Serializable
enum class LcpHealthStatus {
    @SerialName("ok") OK,
    @SerialName("degraded") DEGRADED,
    @SerialName("failed") FAILED,
}

/** `plugin.health`'s answer. */
@Serializable
data class HealthResult(val status: LcpHealthStatus, val reason: String? = null)

/** Who caused an action (SPI `Origin`): an agent's tool call, a plugin page, or the host itself. */
@Serializable
sealed interface LcpOrigin {
    @Serializable
    @SerialName("agent")
    data class Agent(val agentId: String, val conversationId: String? = null, val toolCallId: String? = null) : LcpOrigin

    @Serializable
    @SerialName("view")
    data class View(val elementId: String, val peerId: String) : LcpOrigin

    @Serializable
    @SerialName("host")
    data class Host(val reason: String) : LcpOrigin
}

@Serializable
data class LcpActionContext(val origin: LcpOrigin, val canvasId: String? = null, val elementId: String? = null)

/** `action.invoke`: run [action] of the manifest with schema-valid [input]. */
@Serializable
data class InvokeParams(val action: String, val input: JsonObject, val context: LcpActionContext)

/** A successful action; a failed one is a JSON-RPC error [LcpErrorCode.ACTION_FAILED] with `data.code`. */
@Serializable
data class InvokeResult(val text: String, val structured: JsonObject? = null, val emit: LcpEmit? = null)

/** `data` of an [LcpErrorCode.ACTION_FAILED] error: the plugin's own error code. */
@Serializable
data class LcpActionErrorData(val code: String)

@Serializable
enum class LcpElementEvent {
    @SerialName("moved") MOVED,
    @SerialName("removed") REMOVED,
    @SerialName("focused") FOCUSED,
    @SerialName("viewOpened") VIEW_OPENED,
    @SerialName("viewClosed") VIEW_CLOSED,
}

/** `element.event` (notification): something happened to one of the plugin's own elements. */
@Serializable
data class ElementEventParams(val kind: String, val elementId: String, val event: LcpElementEvent, val frame: CanvasDocumentFrame? = null)

/** `host.settingsChanged` (notification): the owner changed the settings; these are the new resolved ones. */
@Serializable
data class SettingsChangedParams(val settings: JsonObject)

/** Snapshot bytes of an element or an image to place: an uploaded asset, or small inline bytes the host stores. */
@Serializable
sealed interface LcpSnapshot {
    @Serializable
    @SerialName("asset")
    data class Asset(val ref: String) : LcpSnapshot

    @Serializable
    @SerialName("bytes")
    data class Bytes(val mediaType: String, val base64: String) : LcpSnapshot
}

/** Place a new element of the plugin's own [kind] at props schema version [v]. */
@Serializable
data class LcpPlaceElement(
    val kind: String,
    val v: Int,
    val props: JsonObject,
    val fallback: CanvasPluginFallback,
    val ref: String? = null,
    val frame: CanvasDocumentFrame? = null,
    val snapshot: LcpSnapshot? = null,
)

/** Change the `_state` group of one of the plugin's elements; absent fields stay as they are. */
@Serializable
data class LcpUpdateElement(
    val elementId: String,
    val props: JsonObject? = null,
    val fallback: CanvasPluginFallback? = null,
    val snapshot: LcpSnapshot? = null,
    val ref: String? = null,
)

/** Place a core Image element with `_plugin` provenance, so the output outlives the plugin. */
@Serializable
data class LcpPlaceImage(val image: LcpSnapshot, val frame: CanvasDocumentFrame? = null, val provenance: JsonObject? = null)

/** `host.emit` params, and the optional `emit` of an action's result (SPI `PluginEmit`). */
@Serializable
data class LcpEmit(
    val place: List<LcpPlaceElement> = emptyList(),
    val update: List<LcpUpdateElement> = emptyList(),
    val remove: List<String> = emptyList(),
    val placeImages: List<LcpPlaceImage> = emptyList(),
) {
    /** Whether it touches the plugin's own elements (needs `canvas:place`). */
    val touchesElements: Boolean get() = place.isNotEmpty() || update.isNotEmpty() || remove.isNotEmpty()

    /** Whether it hands the host bytes to store (needs `assets:write`). */
    val carriesBytes: Boolean
        get() = placeImages.isNotEmpty() || place.any { it.snapshot is LcpSnapshot.Bytes } || update.any { it.snapshot is LcpSnapshot.Bytes }
}

@Serializable
data class LcpRefusal(val index: Int, val reason: String)

/** What became of an emit: the ids placed, and each refused entry by its index. A refusal never fails the call. */
@Serializable
data class EmitReceipt(val placed: List<String> = emptyList(), val refused: List<LcpRefusal> = emptyList())

@Serializable
data class EmitResult(val receipt: EmitReceipt)

/** `host.putAsset.begin`: announce an upload of [byteSize] bytes of [mediaType]. */
@Serializable
data class PutAssetBeginParams(val mediaType: String, val byteSize: Long)

@Serializable
data class PutAssetBeginResult(val uploadId: String)

/** `host.putAsset.chunk`: the [index]th chunk, in order from 0, at most 1 MiB of base64. */
@Serializable
data class PutAssetChunkParams(val uploadId: String, val index: Int, val base64: String)

/** `host.putAsset.end`: the lowercase hex SHA-256 of all the bytes; the host verifies it. */
@Serializable
data class PutAssetEndParams(val uploadId: String, val sha256: String)

/** The stored asset: `sha256:<hex>`. */
@Serializable
data class PutAssetEndResult(val ref: String)

/** What `host.readElements` reads: the plugin's own elements in full, others' ids, types and frames. */
@Serializable
data class LcpElementQuery(
    val canvasId: String? = null,
    val elementIds: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
    val includeOthers: Boolean = false,
)

@Serializable
data class ReadElementsParams(val query: LcpElementQuery)

@Serializable
data class ReadElementsResult(val elements: List<JsonObject>)

@Serializable
enum class LcpLogLevel {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

/** `host.log` (notification): a line for the host's telemetry, scrubbed before it is kept. */
@Serializable
data class LogParams(val level: LcpLogLevel, val message: String, val fields: Map<String, String> = emptyMap())

/** `$/cancel` (notification, either way): stop the request [id]; it is answered [LcpErrorCode.REQUEST_CANCELLED]. */
@Serializable
data class CancelParams(val id: JsonPrimitive)

/** The params of a method that takes none, and the result of one that answers nothing: `{}`. */
@Serializable
class LcpEmpty {
    override fun equals(other: Any?): Boolean = other is LcpEmpty

    override fun hashCode(): Int = 0

    override fun toString(): String = "{}"
}
