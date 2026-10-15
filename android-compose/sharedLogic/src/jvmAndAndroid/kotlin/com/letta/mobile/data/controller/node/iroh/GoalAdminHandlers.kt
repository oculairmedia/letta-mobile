package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.84: goals have no App Server owner.
 *
 * letta-code 0.34.14 has no goal surface at all: no `/goal` in `SUPPORTED_REMOTE_COMMANDS`, no goal
 * frame in protocol v2, and no goal state in its local backend. Goal mode belonged to the retired
 * admin-shim.
 *
 * - `goal.get` is a read the chat screen issues on every open, so it answers a documented
 *   "no goal" (`goal: null`, `supported: false`) that `GoalStatusResponse` decodes and the chat
 *   renders as no goal banner — instead of the `admin_rest` denial that logged a failure per open.
 * - `goal.command` is a user action, so it stays a typed permanent denial the composer shows.
 */
object GoalAdminHandlers {
    fun register(router: AdminRpcRouter) {
        router.register(GET) { params ->
            val agent = params.requireParam(AdminParamKey("agent_id"))
            buildJsonObject {
                put("source", "app_server")
                put("agent_id", agent)
                put("goal", JsonNull)
                put("supported", false)
            }
        }
        CapabilityUnavailable.denyFailClosed(
            router,
            setOf(COMMAND),
            reason = "letta-code has no goal mode (no /goal command and no goal frame in App Server protocol v2)",
        )
    }

    private const val GET = "goal.get"
    private const val COMMAND = "goal.command"

    val METHODS: Set<String> = setOf(GET, COMMAND)
}
