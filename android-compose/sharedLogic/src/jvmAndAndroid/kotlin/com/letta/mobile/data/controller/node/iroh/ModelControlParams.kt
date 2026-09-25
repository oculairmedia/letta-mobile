package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConversationRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerUpdateModelPayload
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * `model.update` params → upstream `update_model` (letta-mobile-w4q4p).
 *
 * `{agent_id, conversation_id, model_handle?, model_id?, reasoning_effort?}`.
 * An ABSENT `reasoning_effort` leaves the effort alone; an explicit JSON
 * `null` restores the provider default (upstream semantics).
 */
internal object ModelUpdateParams {
    private const val REASONING_EFFORT = "reasoning_effort"

    fun toCommand(params: JsonObject?, requestId: String): AppServerCommand.UpdateModel {
        val payload = AppServerUpdateModelPayload(
            modelId = param(params, AdminParamKey("model_id")),
            modelHandle = param(params, AdminParamKey("model_handle")),
            reasoningEffort = reasoningEffort(params),
        )
        if (payload.isEmpty()) adminError("model.update requires model_handle, model_id, or reasoning_effort")
        return AppServerCommand.UpdateModel(
            requestId = requestId,
            runtime = AppServerConversationRuntimeScope(
                agentId = params.requireParam(AdminParamKey("agent_id")),
                conversationId = params.requireParam(AdminParamKey("conversation_id")),
            ),
            payload = payload,
        )
    }

    private fun AppServerUpdateModelPayload.isEmpty(): Boolean =
        listOf(modelId, modelHandle, reasoningEffort).all { it == null }

    private fun reasoningEffort(params: JsonObject?): JsonElement? {
        if (params == null || REASONING_EFFORT !in params) return null
        val value = params[REASONING_EFFORT]
        if (value == null || value is JsonNull) return AppServerUpdateModelPayload.RESTORE_DEFAULT_REASONING_EFFORT
        val effort = (value as? JsonPrimitive)?.contentOrNull ?: adminError("$REASONING_EFFORT must be a string or null")
        return runCatching { AppServerUpdateModelPayload.reasoningEffortOf(effort) }
            .getOrElse { adminError("$REASONING_EFFORT must be one of ${AppServerUpdateModelPayload.REASONING_EFFORTS}") }
    }
}

/**
 * `model.exposure.set` params: either one `{handle, exposed}` pair or a batch
 * `{models: {"<handle>": true|false}}` (both may be combined).
 */
internal object ModelExposureParams {
    fun changes(params: JsonObject?): Map<String, Boolean> {
        val batch = (params?.get("models") as? JsonObject)?.mapValues { (handle, value) ->
            (value as? JsonPrimitive)?.booleanOrNull ?: adminError("models.$handle must be a boolean")
        }.orEmpty()
        val single = param(params, AdminParamKey("handle"))?.let { handle ->
            val exposed = (params?.get("exposed") as? JsonPrimitive)?.booleanOrNull
                ?: adminError("exposed (boolean) required with handle")
            mapOf(handle to exposed)
        }.orEmpty()
        val merged = batch + single
        if (merged.isEmpty()) adminError("model.exposure.set requires handle+exposed or models")
        return merged
    }
}

/** Secret-free outcome telemetry for the provider/model control surface. */
internal object ModelControlTelemetry {
    private const val TAG = "ModelControl"

    fun providerListed(providers: List<*>) {
        Telemetry.event(TAG, "provider.list", "count" to providers.size.toString())
    }

    fun providerMutation(method: String, providerId: String, success: Boolean) {
        Telemetry.event(TAG, method, "provider_id" to providerId, "success" to success.toString())
    }

    fun modelUpdated(command: AppServerCommand.UpdateModel, success: Boolean, appliedTo: String?) {
        Telemetry.event(
            TAG,
            "model.update",
            "conversation_id" to command.runtime.conversationId,
            "model_handle" to (command.payload.modelHandle ?: command.payload.modelId),
            "success" to success.toString(),
            "applied_to" to appliedTo,
        )
    }

    fun exposureChanged(changed: Int, hiddenTotal: Int) {
        Telemetry.event(TAG, "model.exposure.set", "changed" to changed.toString(), "hidden" to hiddenTotal.toString())
    }

    fun modelListed(returned: Int, includeHidden: Boolean) {
        Telemetry.event(TAG, "model.list", "returned" to returned.toString(), "include_hidden" to includeHidden.toString())
    }
}
