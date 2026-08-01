package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * lgns8.9: run/step history is served from the on-disk local backend store
 * ([LocalBackendRunReader]), not from a REST admin service.
 *
 * admin-shim implemented these routes by reading `<backend>/runs/<id>/run.json`
 * and `steps.jsonl` itself (`lib/runs.ts`), so the store — not the shim — was
 * always the source of truth. With the store injected the wrapper serves them
 * directly; with no store configured they fail closed with a typed capability
 * error and never dial :8291.
 */
object RunAdminHandlers {
    internal fun register(router: AdminRpcRouter, store: LocalBackendAdminStore?) {
        if (store == null) {
            CapabilityUnavailable.register(router, METHODS, service = "local_backend_store")
            return
        }
        router.register("run.list") { params ->
            store.listRunsProjected(runQuery(params))
                ?: adminError("run.list could not read the local backend run store")
        }
        router.register("run.get") { params ->
            val runId = params.requireParam(AdminParamKey("run_id"))
            store.getRunProjected(runId) ?: adminError("run $runId not found")
        }
        router.register("step.list") { params ->
            val runId = params.requireParam(AdminParamKey("run_id"))
            // admin-shim 404s the steps route for an unknown run rather than
            // serving an empty page; preserve that so callers can tell "no such
            // run" from "run with no steps".
            if (!store.runExists(runId)) adminError("run $runId not found")
            store.listStepsProjected(runId, stepQuery(params))
                ?: adminError("step.list could not read the local backend run store")
        }
    }

    /** Port of admin-shim `handleRunsList`'s query-param mapping. */
    private fun runQuery(params: JsonObject?): RunQuery = RunQuery(
        agentId = param(params, AdminParamKey("agent_id")),
        conversationId = param(params, AdminParamKey("conversation_id")),
        active = param(params, AdminParamKey("active"))?.toBooleanStrictOrNull(),
        background = param(params, AdminParamKey("background"))?.toBooleanStrictOrNull(),
        statuses = stringList(params, "statuses"),
        stopReason = param(params, AdminParamKey("stop_reason")),
        before = param(params, AdminParamKey("before")),
        after = param(params, AdminParamKey("after")),
        limit = param(params, AdminParamKey("limit"))?.toIntOrNull() ?: RunQuery.DEFAULT_LIMIT,
        order = param(params, AdminParamKey("order")) ?: "desc",
        includeArchived = param(params, AdminParamKey("include_archived"))?.toBooleanStrictOrNull() ?: false,
    )

    /** Port of admin-shim `handleRunSteps`'s query-param mapping. */
    private fun stepQuery(params: JsonObject?): StepQuery = StepQuery(
        before = param(params, AdminParamKey("before")),
        after = param(params, AdminParamKey("after")),
        limit = param(params, AdminParamKey("limit"))?.toIntOrNull() ?: StepQuery.DEFAULT_LIMIT,
        order = param(params, AdminParamKey("order")) ?: "desc",
    )

    /** Repeated query params arrive as either a JSON array or a single string. */
    private fun stringList(params: JsonObject?, key: String): List<String> {
        val value = params?.get(key) ?: return emptyList()
        return if (value is JsonArray) {
            value.jsonArray.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        } else {
            listOfNotNull(runCatching { value.jsonPrimitive.content }.getOrNull()).filter { it.isNotEmpty() }
        }
    }

    val METHODS: Set<String> = setOf(
        "run.list",
        "run.get",
        "step.list",
    )
}
