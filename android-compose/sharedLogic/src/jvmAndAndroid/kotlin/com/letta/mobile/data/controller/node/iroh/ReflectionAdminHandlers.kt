package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.compaction.executeCommandAnswer
import com.letta.mobile.data.dreaming.AdminRpcDreamingRepository
import com.letta.mobile.data.dreaming.DreamMerge
import com.letta.mobile.data.dreaming.DreamOutcome
import com.letta.mobile.data.dreaming.DreamRunStart
import com.letta.mobile.data.dreaming.DreamSettings
import com.letta.mobile.data.dreaming.DreamSettingsScope
import com.letta.mobile.data.dreaming.DreamTrigger
import com.letta.mobile.data.dreaming.DreamingRpc
import com.letta.mobile.data.dreaming.toDreamOutcome
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Native reflection (dreaming) parity over Iroh admin_rpc (lgns8.16, letta-mobile-iczm7).
 *
 *  - `reflection.get` / `reflection.set` carry the agent's settings through letta-code's
 *    runtime-scoped `get_reflection_settings` / `set_reflection_settings`: trigger, step_count and,
 *    when the client sends them, merge, merge_instructions and scope.
 *  - `reflection.run` starts a dream now through letta-code's own `/dream` (`execute_command
 *    dream`), which on the local backend runs a local reflection.
 *
 * A missing `conversation_id` is the agent's `default` conversation. Native-only: lettashim never
 * exposed reflection, so without a live App Server client every method answers
 * `capability_unavailable` rather than dialing the shim. Params are never logged.
 */
internal object ReflectionAdminHandlers {
    val methods: Set<String> = setOf(DreamingRpc.GET, DreamingRpc.SET, DreamingRpc.RUN)

    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        if (nativeClient == null) {
            CapabilityUnavailable.register(router, methods, NATIVE_CLIENT)
            return
        }
        router.register(DreamingRpc.GET) { params -> get(nativeClient, params) }
        router.register(DreamingRpc.SET) { params -> set(nativeClient, params) }
        router.register(DreamingRpc.RUN) { params -> run(nativeClient, params) }
    }

    private suspend fun get(client: AppServerClient, params: JsonObject?): JsonElement {
        val response = client.getReflectionSettings(
            AppServerCommand.GetReflectionSettings(requestId = NativeAdmin.requestId(), runtime = scope(params)),
        )
        if (!response.success) adminError(response.error ?: "get_reflection_settings failed")
        return buildJsonObject { put(DreamingRpc.REFLECTION_SETTINGS, response.reflectionSettings ?: JsonNull) }
    }

    private suspend fun set(client: AppServerClient, params: JsonObject?): JsonElement {
        val response = client.setReflectionSettings(
            AppServerCommand.SetReflectionSettings(
                requestId = NativeAdmin.requestId(),
                runtime = scope(params),
                settings = settings(params),
                scope = param(params, AdminParamKey(DreamingRpc.SCOPE))?.let { raw ->
                    DreamSettingsScope.fromWire(raw)?.wire ?: adminError("invalid_scope: $raw (local_project, global or both)")
                },
            ),
        )
        if (!response.success) adminError(response.error ?: "set_reflection_settings failed")
        return buildJsonObject {
            put(DreamingRpc.REFLECTION_SETTINGS, response.reflectionSettings ?: JsonNull)
            response.scope?.let { put(DreamingRpc.SCOPE, it) }
        }
    }

    /**
     * The `settings` letta-code validates. merge and merge_instructions go only when the client
     * sent them, so an older client's trigger change never resets a merge choice made elsewhere.
     */
    internal fun settings(params: JsonObject?): JsonObject {
        val rawTrigger = params.requireParam(AdminParamKey(DreamSettings.TRIGGER))
        val trigger = DreamTrigger.fromWire(rawTrigger) ?: adminError("invalid_trigger: $rawTrigger (off, step-count or compaction-event)")
        val stepCount = param(params, AdminParamKey(DreamSettings.STEP_COUNT))?.toIntOrNull()?.takeIf { it >= DreamSettings.MIN_STEP_COUNT }
            ?: adminError("step_count is required and must be a positive integer")
        val merge = param(params, AdminParamKey(DreamSettings.MERGE))?.let { raw ->
            DreamMerge.fromWire(raw) ?: adminError("invalid_merge: $raw (auto or explicit)")
        }
        val instructions = param(params, AdminParamKey(DreamSettings.MERGE_INSTRUCTIONS))
        return buildJsonObject {
            put(DreamSettings.TRIGGER, trigger.wire)
            put(DreamSettings.STEP_COUNT, stepCount)
            merge?.let { put(DreamSettings.MERGE, it.wire) }
            instructions?.let { put(DreamSettings.MERGE_INSTRUCTIONS, it) }
        }
    }

    internal suspend fun run(client: AppServerClient, params: JsonObject?): JsonElement {
        val runtime = scope(params)
        val outcome = runGuarded {
            client.executeCommandAnswer(
                AppServerCommand.ExecuteCommand(
                    requestId = NativeAdmin.requestId(),
                    commandId = DreamingRpc.COMMAND_ID,
                    args = DreamingRpc.commandArgs(param(params, AdminParamKey(DreamingRpc.INSTRUCTION))),
                    runtime = runtime,
                ),
            ).toDreamOutcome()
        }
        val start = when (outcome) {
            is DreamOutcome.Done -> outcome.value
            is DreamOutcome.Unsupported -> adminError(AdminRpcDreamingRepository.NO_DREAM_COMMAND)
            is DreamOutcome.Failed -> adminError(AdminRpcDreamingRepository.FAILED_PREFIX + outcome.message)
        }
        Telemetry.event("IrohNode", "reflection_run.ok", "status" to start.status.name)
        return AppServerProtocol.json.encodeToJsonElement(DreamRunStart.serializer(), start)
    }

    private fun scope(params: JsonObject?): AppServerRuntimeScope =
        AppServerRuntimeScope(
            agentId = params.requireParam(AdminParamKey(DreamingRpc.AGENT_ID)),
            conversationId = param(params, AdminParamKey(DreamingRpc.CONVERSATION_ID))
                ?.takeIf { it.isNotBlank() } ?: ConversationCompactRpc.DEFAULT_CONVERSATION,
        )

    private suspend fun <T> runGuarded(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unsupported: UnsupportedOperationException) {
        fail("unsupported", unsupported, "capability_unavailable: the App Server cannot run commands")
    } catch (timeout: AppServerRequestTimeoutException) {
        fail("timeout", timeout, "${AdminRpcDreamingRepository.FAILED_PREFIX}the App Server did not answer")
    } catch (error: Exception) {
        fail("failed", error, "${AdminRpcDreamingRepository.FAILED_PREFIX}the host could not reach the App Server")
    }

    private fun fail(outcome: String, error: Exception, message: String): Nothing {
        Telemetry.event(
            "IrohNode", "reflection_run.$outcome",
            "class" to error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
        adminError(message)
    }

    private const val NATIVE_CLIENT = "native App Server client"
}
