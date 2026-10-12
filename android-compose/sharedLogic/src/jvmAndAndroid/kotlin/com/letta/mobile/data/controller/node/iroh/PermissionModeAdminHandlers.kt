package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.runtime.PermissionModeRpc
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * letta-mobile-bzvro.40: lets an Iroh client read and change the permission mode of a conversation's
 * runtime on this host ([PermissionModeRpc]). The host runs every relayed turn, so its runtime's mode
 * is the one that decides which tool calls park for the person.
 *
 * `set` goes through [AppServerController.changePermissionMode]: `change_device_state` on a running
 * runtime (confirmed by the App Server's `update_device_status`), else a `runtime_start` carrying the
 * mode, and the choice is pinned so a later `runtime_start` from any client cannot run it looser.
 *
 * Capabilities: `get` is a chat read, `set` a conversation-management write (it widens or narrows
 * what the agent may do on this host without asking). Params are never logged.
 */
internal object PermissionModeAdminHandlers {
    val methods: Set<String> = PermissionModeRpc.methods

    fun register(router: AdminRpcRouter, controller: AppServerController?) {
        if (controller == null) {
            CapabilityUnavailable.register(router, methods, "App Server controller")
            return
        }
        router.register(PermissionModeRpc.GET) { params -> get(controller, runtimeOf(params)) }
        router.register(PermissionModeRpc.SET) { params -> set(controller, runtimeOf(params), modeOf(params)) }
        router.featureCapabilities += PermissionModeRpc.CAPABILITY
    }

    internal suspend fun get(controller: AppServerController, runtime: AppServerRuntimeScope): JsonElement =
        PermissionModeRpc.result(controller.permissionModeOf(runtime))

    internal suspend fun set(controller: AppServerController, runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): JsonElement {
        val confirmed = try {
            controller.changePermissionMode(runtime, mode)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unsupported: UnsupportedOperationException) {
            adminError("capability_unavailable: this host cannot change permission modes")
        } catch (error: Exception) {
            Telemetry.event("IrohNode", "permission_mode.set_failed", "class" to error::class.simpleName, level = Telemetry.Level.WARN)
            adminError("permission_mode_failed: the host could not reach the App Server")
        }
        Telemetry.event("IrohNode", "permission_mode.set", "mode" to PermissionModeRpc.wire(mode), "confirmed" to confirmed)
        return PermissionModeRpc.result(controller.permissionModeOf(runtime) ?: mode.takeIf { confirmed }, confirmed)
    }

    private fun runtimeOf(params: JsonObject?): AppServerRuntimeScope = AppServerRuntimeScope(
        agentId = params.requireParam(AdminParamKey(PermissionModeRpc.AGENT_ID)),
        conversationId = params.requireParam(AdminParamKey(PermissionModeRpc.CONVERSATION_ID)),
    )

    private fun modeOf(params: JsonObject?): AppServerPermissionMode {
        val raw = params.requireParam(AdminParamKey(PermissionModeRpc.MODE))
        return AppServerPermissionMode.fromWireValue(raw) ?: adminError("invalid_mode: unsupported permission mode")
    }
}
