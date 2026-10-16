package com.letta.mobile.data.dreaming

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-iczm7: dreaming through the Iroh host's `reflection.get/set/run` ([DreamingRpc]).
 *
 * A host that predates a method answers "Unknown method" ([DreamUnsupportedReason.HostNeedsUpdate]:
 * `reflection.run` and the merge fields are newer than `reflection.get/set`). One without a native
 * App Server client answers `capability_unavailable` ([DreamUnsupportedReason.NoNativeAppServer]);
 * a transport with no admin_rpc at all is [DreamUnsupportedReason.NoConnection].
 */
class AdminRpcDreamingRepository(
    private val rpc: AdminRpcInvoker,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DreamingRepository {
    override suspend fun settings(target: DreamTarget): DreamOutcome<DreamSettings> =
        call(DreamingRpc.GET, target) {}.thenSettings()

    override suspend fun save(target: DreamTarget, change: DreamSettingsChange): DreamOutcome<DreamSettings> =
        call(DreamingRpc.SET, target) {
            change.settings.toWire().forEach { (key, value) -> put(key, value) }
            change.scope?.let { put(DreamingRpc.SCOPE, it.wire) }
        }.thenSettings()

    override suspend fun runNow(target: DreamTarget, instruction: String?): DreamOutcome<DreamRunStart> =
        when (val outcome = call(DreamingRpc.RUN, target) { instruction?.let { put(DreamingRpc.INSTRUCTION, it) } }) {
            is DreamOutcome.Done -> runCatching { json.decodeFromJsonElement(DreamRunStart.serializer(), outcome.value) }
                .fold({ DreamOutcome.Done(it) }, { DreamOutcome.Failed("The host sent an unreadable dream answer") })
            is DreamOutcome.Unsupported -> outcome
            is DreamOutcome.Failed -> outcome
        }

    private suspend fun call(method: String, target: DreamTarget, extra: JsonObjectBuilder.() -> Unit): DreamOutcome<JsonElement> {
        val params = buildJsonObject {
            put(DreamingRpc.AGENT_ID, target.agentId.value)
            put(DreamingRpc.CONVERSATION_ID, target.wireConversationId)
            extra()
        }
        return runCatchingCancellable { rpc.invoke(method, params) }.fold(
            onSuccess = { result -> result?.let { DreamOutcome.Done(it) } ?: DreamOutcome.Failed("The host returned no result") },
            onFailure = { classify(it.message.orEmpty()) },
        )
    }

    private fun DreamOutcome<JsonElement>.thenSettings(): DreamOutcome<DreamSettings> = when (this) {
        is DreamOutcome.Done -> {
            val settings = runCatching { value.jsonObject[DreamingRpc.REFLECTION_SETTINGS] as? JsonObject }.getOrNull()
            DreamSettings.fromWire(settings)?.let { DreamOutcome.Done(it) } ?: DreamOutcome.Failed("letta-code sent no dreaming settings")
        }
        is DreamOutcome.Unsupported -> this
        is DreamOutcome.Failed -> this
    }

    private fun classify(message: String): DreamOutcome<Nothing> = when {
        AdminRpcErrors.isUnknownMethod(message) -> DreamOutcome.Unsupported(DreamUnsupportedReason.HostNeedsUpdate)
        message.contains(NO_ADMIN_RPC) || message.contains(NOT_CONNECTED, ignoreCase = true) ->
            DreamOutcome.Unsupported(DreamUnsupportedReason.NoConnection)
        message.contains(NO_DREAM_COMMAND) -> DreamOutcome.Unsupported(DreamUnsupportedReason.RuntimeTooOld)
        message.contains(CAPABILITY_UNAVAILABLE) ->
            DreamOutcome.Unsupported(DreamUnsupportedReason.NoNativeAppServer)
        else -> DreamOutcome.Failed(message.removePrefix(FAILED_PREFIX).ifBlank { "The host couldn't change dreaming" })
    }

    internal companion object {
        const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
        const val NO_DREAM_COMMAND = "capability_unavailable: this App Server has no /dream"
        const val NO_ADMIN_RPC = "admin_rpc is not supported"
        const val NOT_CONNECTED = "Not connected"
        const val FAILED_PREFIX = "dream_failed: "
    }
}
