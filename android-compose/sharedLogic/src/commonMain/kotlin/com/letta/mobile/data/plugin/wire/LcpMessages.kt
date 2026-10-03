package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.EmitReceipt
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.HostInfo
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.PluginElementView
import com.letta.mobile.plugin.api.PluginInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The wire-only envelopes of LCP v1 (plan section 5): params and results that have no SPI type of
// their own. Everything inside them, and every other method's params and result, is a
// `:plugin-api` DTO (ActionCall, ActionResult.Ok, ElementEvent, PluginEmit, PluginHealth, ...).

/**
 * `plugin.initialize`: the handshake (SPI: the [com.letta.mobile.plugin.api.PluginHost] properties
 * `LcpMethod.INITIALIZE_PARAMS`). The host offers [contractVersions] (every version it speaks) with
 * [contractVersion] its best; the plugin answers the version it chose from them, or
 * [LcpErrorCode.CONTRACT_MISMATCH] when it speaks none. [settings] are resolved and validated;
 * secrets are never here (env for `process`, headers for `service`).
 */
@Serializable
data class InitializeParams(
    val contractVersion: Int,
    val contractVersions: List<Int>,
    val pluginId: String,
    val settings: JsonObject,
    val hostInfo: HostInfo,
)

@Serializable
data class InitializeResult(val ok: Boolean, val contractVersion: Int, val pluginInfo: PluginInfo = PluginInfo())

/** `host.settingsChanged` (notification): the new resolved settings (SPI `CanvasPlugin.onSettingsChanged`). */
@Serializable
data class SettingsChangedParams(val settings: JsonObject)

/** `data` of an [LcpErrorCode.ACTION_FAILED] error: the plugin's own error code (SPI `ActionResult.Error.code`). */
@Serializable
data class LcpActionErrorData(val code: String)

/** `host.emit`'s answer. */
@Serializable
data class EmitResult(val receipt: EmitReceipt)

/** `host.putAsset.begin`: announce an upload of [byteSize] bytes of [mediaType]. */
@Serializable
data class PutAssetBeginParams(val mediaType: String, val byteSize: Long)

@Serializable
data class PutAssetBeginResult(val uploadId: String)

/** `host.putAsset.chunk`: the [index]th chunk, in order from 0, at most 1 MiB of bytes as base64. */
@Serializable
data class PutAssetChunkParams(val uploadId: String, val index: Int, val base64: String)

/** `host.putAsset.end`: the lowercase hex SHA-256 of all the bytes; the host verifies it. */
@Serializable
data class PutAssetEndParams(val uploadId: String, val sha256: String)

/** The stored asset: `sha256:<hex>`. */
@Serializable
data class PutAssetEndResult(val ref: String)

@Serializable
data class ReadElementsParams(val query: ElementQuery)

@Serializable
data class ReadElementsResult(val elements: List<PluginElementView>)

/** `host.log` (notification): a line for the host's telemetry, scrubbed before it is kept (SPI `PluginHost.log`). */
@Serializable
data class LogParams(val level: LogLevel, val message: String, val fields: Map<String, String> = emptyMap())

/** `$/cancel` (notification, either way): stop the request [id]; it is answered [LcpErrorCode.REQUEST_CANCELLED]. */
@Serializable
data class CancelParams(val id: JsonPrimitive)

/** The params of a method that takes none, and the result of one that answers nothing: `{}`. */
@Serializable
object LcpEmpty {
    override fun toString(): String = "{}"
}
