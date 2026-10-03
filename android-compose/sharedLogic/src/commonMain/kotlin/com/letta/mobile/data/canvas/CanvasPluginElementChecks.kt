package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.CanvasSceneIndex.Companion.idOrNull
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementSchema
import kotlinx.serialization.json.JsonObject

/**
 * The [CanvasSceneState] invariants of a board's plugin elements (`_pluginElements`): each entry
 * decodes and keeps the envelope, stays small, the board holds at most
 * [CanvasSceneLimits.MAX_PLUGIN_ELEMENTS] of them, and no id is shared with an element or a note.
 */
internal object CanvasPluginElementChecks {
    fun all(index: CanvasSceneIndex): List<CanvasStateViolation> =
        count(index) + index.pluginEntries.flatMap(::entry) + sharedIds(index)

    private fun count(index: CanvasSceneIndex): List<CanvasStateViolation> = listOfNotNull(
        CanvasStateViolation(
            CanvasStateInvariant.PLUGIN_ELEMENT_COUNT, CanvasSceneState.SCENE,
            "has ${index.pluginEntries.size} plugin elements, over ${CanvasSceneLimits.MAX_PLUGIN_ELEMENTS}",
        ).takeIf { index.pluginEntries.size > CanvasSceneLimits.MAX_PLUGIN_ELEMENTS },
    )

    private fun entry(entry: JsonObject): List<CanvasStateViolation> {
        val subject = entry.idOrNull() ?: UNNAMED
        val chars = entry.toString().length
        val problems = CanvasPluginElementSchema.checkEntry(entry).map { problem ->
            CanvasStateViolation(CanvasStateInvariant.PLUGIN_ELEMENT_DECODES, subject + problem.path, "${problem.path} ${problem.reason}")
        }
        return problems + listOfNotNull(
            CanvasStateViolation(
                CanvasStateInvariant.PLUGIN_ELEMENT_SIZE, subject, "is $chars chars, over ${CanvasSceneLimits.MAX_PLUGIN_ELEMENT_CHARS}",
            ).takeIf { chars > CanvasSceneLimits.MAX_PLUGIN_ELEMENT_CHARS },
        )
    }

    private fun sharedIds(index: CanvasSceneIndex): List<CanvasStateViolation> =
        index.pluginElementIds.filter { index.hasElement(it) || index.hasDocument(it) }.map { id ->
            CanvasStateViolation(
                CanvasStateInvariant.PLUGIN_ELEMENT_DUPLICATE_ID, id,
                "is the id of a plugin element and of ${if (index.hasElement(id)) "an element" else "a note"}; ids are unique across the board",
            )
        }

    private const val UNNAMED = "pluginElement"
}
