package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Native reflection-settings parity over Iroh admin_rpc (lgns8.16).
 *
 * Carries the agent's reflection settings (trigger + step_count) through the
 * native App Server path via `get_reflection_settings`/`set_reflection_settings`
 * (runtime-scoped). Native-ONLY: lettashim never exposed reflection settings,
 * so without a live App Server client these fail with a clear capability error
 * rather than dialing the shim.
 */
internal object ReflectionAdminHandlers {
    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        fun requireClient(): AppServerClient =
            nativeClient ?: adminError("reflection settings require the native App Server client")

        fun scope(params: JsonObject?): AppServerRuntimeScope =
            AppServerRuntimeScope(
                agentId = params.requireParam(AdminParamKey("agent_id")),
                conversationId = params.requireParam(AdminParamKey("conversation_id")),
            )

        router.register("reflection.get") { params ->
            val response = requireClient().getReflectionSettings(
                AppServerCommand.GetReflectionSettings(
                    requestId = NativeAdmin.requestId(),
                    runtime = scope(params),
                ),
            )
            if (!response.success) adminError(response.error ?: "get_reflection_settings failed")
            buildJsonObject { put("reflection_settings", response.reflectionSettings ?: JsonNull) }
        }

        router.register("reflection.set") { params ->
            val trigger = params.requireParam(AdminParamKey("trigger"))
            val stepCount = param(params, AdminParamKey("step_count"))?.toIntOrNull()
                ?: adminError("step_count is required and must be an integer")
            val response = requireClient().setReflectionSettings(
                AppServerCommand.SetReflectionSettings(
                    requestId = NativeAdmin.requestId(),
                    runtime = scope(params),
                    settings = buildJsonObject {
                        put("trigger", JsonPrimitive(trigger))
                        put("step_count", JsonPrimitive(stepCount))
                    },
                    scope = param(params, AdminParamKey("scope")),
                ),
            )
            if (!response.success) adminError(response.error ?: "set_reflection_settings failed")
            buildJsonObject {
                put("reflection_settings", response.reflectionSettings ?: JsonNull)
                response.scope?.let { put("scope", it) }
            }
        }
    }

    val methods: Set<String> = setOf("reflection.get", "reflection.set")
}
