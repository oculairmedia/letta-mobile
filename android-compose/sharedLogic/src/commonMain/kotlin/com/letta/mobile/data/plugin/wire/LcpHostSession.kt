package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.ElementEvent
import com.letta.mobile.plugin.api.ElementQuery
import com.letta.mobile.plugin.api.EmitReceipt
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.PluginElementView
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.PluginHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What the host does for a plugin's calls (SPI `PluginHost`); the guards have already held each call. */
interface LcpHostHandlers {
    suspend fun emit(emit: PluginEmit): EmitReceipt

    /** Stores a verified upload and answers its ref (`sha256:<hex>`). */
    suspend fun storeAsset(asset: LcpUploadedAsset): String

    suspend fun readElements(query: ElementQuery): List<PluginElementView>

    /** A scrubbed log line. */
    suspend fun log(line: LogParams)
}

/** What the host knows of the plugin at the other end: what it may do, and the secrets its output is held to. */
data class LcpHostBinding(val capabilities: Set<PluginCapability>, val scrubber: SecretScrubber, val handlers: LcpHostHandlers)

/**
 * Contract version negotiation (plan section 3.2): the host offers every version it speaks, the
 * plugin answers one of them or refuses with [LcpErrorCode.CONTRACT_MISMATCH].
 */
object LcpHandshake {
    /** The plugin's side: the best version both speak, or the refusal to answer. */
    fun choose(offered: List<Int>, spoken: List<Int>): Int =
        offered.filter { it in spoken }.maxOrNull() ?: throw mismatch("the plugin speaks contract $spoken, the host offered $offered", spoken)

    /** The host's side: [result] must name a version the host offered. */
    fun accept(params: InitializeParams, result: InitializeResult) {
        if (!result.ok || result.contractVersion !in params.contractVersions) {
            throw mismatch("the plugin answered contract ${result.contractVersion}, the host offered ${params.contractVersions}", params.contractVersions)
        }
    }

    private fun mismatch(message: String, supported: List<Int>): LcpCallException = LcpCallException(
        LcpErrorCode.CONTRACT_MISMATCH,
        message,
        buildJsonObject { put("supported", JsonArray(supported.map(::JsonPrimitive))) },
    )
}

/**
 * The host's end of one plugin connection: a [LcpPeer] held by the session state machine, the
 * capability check and the secret guard, serving the plugin's calls from [LcpHostBinding.handlers]
 * and calling the plugin's methods with their deadlines. The driver (letta-mobile-s416w.28) opens
 * one per process or service connection.
 */
class LcpHostSession(transport: LcpTransport, binding: LcpHostBinding, scope: CoroutineScope) {
    private val session = PluginWireSession()
    private val uploads = LcpAssetUploads()
    private val guard = LcpGuards.chain(session, LcpCapabilityGuard(binding.capabilities), LcpSecretGuard(binding.scrubber))
    val peer = LcpPeer(transport, LcpPeerConfig(LcpSide.HOST, guard), scope)

    val state: StateFlow<LcpSessionState> get() = session.state

    init {
        serve(binding.handlers)
        peer.start()
    }

    private fun serve(handlers: LcpHostHandlers) {
        peer.serve(LcpCalls.EMIT) { EmitResult(handlers.emit(it)) }
        peer.serve(LcpCalls.PUT_ASSET_BEGIN) { uploads.begin(it) }
        peer.serve(LcpCalls.PUT_ASSET_CHUNK) { uploads.chunk(it).let { LcpEmpty } }
        peer.serve(LcpCalls.PUT_ASSET_END) { PutAssetEndResult(handlers.storeAsset(uploads.end(it))) }
        peer.serve(LcpCalls.READ_ELEMENTS) { ReadElementsResult(handlers.readElements(it.query)) }
        peer.serve(LcpCalls.LOG) { handlers.log(it) }
    }

    /** The handshake; a version both sides do not speak closes the session. */
    suspend fun initialize(params: InitializeParams): InitializeResult = closingOnFailure {
        peer.call(LcpCalls.INITIALIZE, params).also { LcpHandshake.accept(params, it) }
    }

    suspend fun activate() {
        peer.call(LcpCalls.ACTIVATE, LcpEmpty)
    }

    suspend fun health(): PluginHealth = peer.call(LcpCalls.HEALTH, LcpEmpty)

    /** Runs an action (SPI `CanvasPlugin.invoke`); the plugin's own failure is [ActionResult.Error], a protocol failure throws. */
    suspend fun invoke(call: ActionCall): ActionResult = try {
        peer.call(LcpCalls.INVOKE, call)
    } catch (e: LcpCallException) {
        if (e.code != LcpErrorCode.ACTION_FAILED) throw e
        ActionResult.Error(actionCodeOf(e.error), e.error.message)
    }

    suspend fun elementEvent(event: ElementEvent) = peer.notify(LcpCalls.ELEMENT_EVENT, event)

    suspend fun settingsChanged(params: SettingsChangedParams) = peer.notify(LcpCalls.SETTINGS_CHANGED, params)

    /**
     * Drains, then deactivates: new actions and events are refused at once, the calls in flight
     * either way get up to the deactivate deadline to finish, then `plugin.deactivate` is sent and
     * the session closes whatever it answers.
     */
    suspend fun deactivate() {
        session.stop()
        withTimeoutOrNull(LcpMethod.DEACTIVATE.deadline ?: LcpPeerLimits().defaultDeadline) { peer.awaitIdle() }
        closingOnFailure { peer.call(LcpCalls.DEACTIVATE, LcpEmpty) }
        close()
    }

    fun close() {
        session.close()
        peer.close()
    }

    private suspend fun <T> closingOnFailure(block: suspend () -> T): T = try {
        block()
    } catch (e: LcpCallException) {
        close()
        throw e
    }

    private fun actionCodeOf(error: JsonRpcError): String =
        ((error.data as? JsonObject)?.get("code"))?.jsonPrimitive?.contentOrNull ?: ActionResult.Error.INTERNAL
}
