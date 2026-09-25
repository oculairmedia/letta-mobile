package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AppServerListModelsAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Decorates the adapted `model.list` rows (letta-mobile-w4q4p):
 *
 *  - `exposed`: the wrapper exposure decision for the row's handle; hidden rows
 *    are dropped unless the caller asked for `include_hidden`.
 *  - `reasoning_efforts`: upstream advertises one `list_models` entry per
 *    reasoning variant (same handle, different `updateArgs.reasoning_effort`),
 *    and the adapter collapses those into one row per handle. The distinct
 *    efforts are re-attached here so a picker can offer them for
 *    `model.update`.
 */
internal object ModelListProjection {
    const val EXPOSED_KEY = "exposed"
    const val REASONING_EFFORTS_KEY = "reasoning_efforts"

    fun decorate(
        adapted: JsonArray,
        rawEntries: JsonArray,
        exposure: ModelExposureStore,
        includeHidden: Boolean,
    ): JsonArray {
        val efforts = reasoningEffortsByHandle(rawEntries)
        val decisions = exposure.decisions()
        val rows = adapted.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val handle = rowHandle(row)
            val exposed = handle?.let { decisions[it] } ?: true
            if (!exposed && !includeHidden) return@mapNotNull null
            decorateRow(row, exposed, handle?.let { efforts[it] })
        }
        return JsonArray(rows)
    }

    /** The key exposure decisions use: the row's selection handle, else its id. */
    fun rowHandle(row: JsonObject): String? =
        stringAt(row, "handle") ?: stringAt(row, "id")

    private fun decorateRow(row: JsonObject, exposed: Boolean, efforts: List<String>?): JsonObject {
        val extra = buildMap {
            put(EXPOSED_KEY, JsonPrimitive(exposed))
            if (!efforts.isNullOrEmpty()) put(REASONING_EFFORTS_KEY, JsonArray(efforts.map(::JsonPrimitive)))
        }
        return JsonObject(row + extra)
    }

    /** Distinct `updateArgs.reasoning_effort` values per selection handle, in upstream order. */
    fun reasoningEffortsByHandle(rawEntries: JsonArray): Map<String, List<String>> {
        val byHandle = LinkedHashMap<String, LinkedHashSet<String>>()
        AppServerListModelsAdapter.decodeEntriesWithRaw(rawEntries).forEach { (entry, raw) ->
            val effort = entry.updateArgs?.let { stringAt(it, "reasoning_effort") } ?: return@forEach
            val handle = AppServerListModelsAdapter.selectionHandle(entry, raw) ?: return@forEach
            byHandle.getOrPut(handle) { LinkedHashSet() } += effort
        }
        return byHandle.mapValues { (_, values) -> values.toList() }
    }

    private fun stringAt(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
