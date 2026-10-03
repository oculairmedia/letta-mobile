package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.PlaceElement
import com.letta.mobile.plugin.api.PlaceImage
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.SnapshotSource
import com.letta.mobile.plugin.api.UpdateElement
import com.letta.mobile.plugin.testkit.ConformanceManifest.Companion.ASSETS_WRITE
import com.letta.mobile.plugin.testkit.ConformanceManifest.Companion.CANVAS_PLACE
import kotlinx.serialization.json.JsonObject

/** One refused entry of an emit: its [index] in the flattened order (place, update, remove, placeImages). */
internal data class EmitProblem(val index: Int, val rule: ConformanceRule, val reason: String)

/**
 * What the host's placement compiler refuses in an emit, as the kit sees it (plan section 6.3):
 * capabilities first, then each entry against the manifest's element kinds and the plugin's own
 * elements ([ownKinds]: element id to kind).
 */
internal class EmitRules(private val manifest: ConformanceManifest, private val ownKinds: Map<String, String>) {
    fun problems(emit: PluginEmit): List<EmitProblem> {
        val updateStart = emit.place.size
        val removeStart = updateStart + emit.update.size
        val imageStart = removeStart + emit.remove.size
        return emit.place.flatMapIndexed { i, place -> placeProblems(place).at(i) } +
            emit.update.flatMapIndexed { i, update -> updateProblems(update).at(updateStart + i) } +
            emit.remove.flatMapIndexed { i, id -> removeProblems(id).at(removeStart + i) } +
            emit.placeImages.flatMapIndexed { i, image -> imageProblems(image).at(imageStart + i) }
    }

    private fun List<Pair<ConformanceRule, String>>.at(index: Int): List<EmitProblem> =
        map { (rule, reason) -> EmitProblem(index, rule, reason) }

    private fun placeProblems(place: PlaceElement): List<Pair<ConformanceRule, String>> {
        val kind = manifest.elements[place.kind]
            ?: return placeCapability() + (ConformanceRule.EMIT to "kind '${place.kind}' is not declared in the manifest's elements")
        val version = (ConformanceRule.EMIT to "kind '${place.kind}' is at schemaVersion ${kind.schemaVersion}, not v ${place.v}")
            .takeIf { place.v != kind.schemaVersion }
        val title = (ConformanceRule.EMIT to "kind '${place.kind}' has a blank fallback title").takeIf { place.fallback.title.isBlank() }
        return placeCapability() + listOfNotNull(version, title) + propsProblems(place.kind, place.props) +
            snapshotProblems(place.snapshot)
    }

    private fun updateProblems(update: UpdateElement): List<Pair<ConformanceRule, String>> {
        val kind = ownKinds[update.elementId]
            ?: return placeCapability() + (ConformanceRule.EMIT to "update names '${update.elementId}', not one of the plugin's elements")
        return placeCapability() + update.props?.let { propsProblems(kind, it) }.orEmpty() + snapshotProblems(update.snapshot)
    }

    private fun removeProblems(elementId: String): List<Pair<ConformanceRule, String>> =
        placeCapability() + listOfNotNull(
            (ConformanceRule.EMIT to "remove names '$elementId', not one of the plugin's elements").takeIf { elementId !in ownKinds },
        )

    private fun imageProblems(image: PlaceImage): List<Pair<ConformanceRule, String>> = listOfNotNull(
        (ConformanceRule.CAPABILITY to "placeImages needs the $ASSETS_WRITE capability").takeIf { !manifest.has(ASSETS_WRITE) },
    )

    private fun propsProblems(kind: String, props: JsonObject): List<Pair<ConformanceRule, String>> {
        val schema = manifest.elements[kind]?.props ?: return emptyList()
        return JsonSchemaSubset.problems(schema, props, "/props").map { ConformanceRule.EMIT to "kind '$kind': $it" }
    }

    private fun snapshotProblems(snapshot: SnapshotSource?): List<Pair<ConformanceRule, String>> = listOfNotNull(
        (ConformanceRule.CAPABILITY to "a snapshot from bytes needs the $ASSETS_WRITE capability")
            .takeIf { snapshot is SnapshotSource.Bytes && !manifest.has(ASSETS_WRITE) },
    )

    private fun placeCapability(): List<Pair<ConformanceRule, String>> = listOfNotNull(
        (ConformanceRule.CAPABILITY to "emitting elements needs the $CANVAS_PLACE capability").takeIf { !manifest.has(CANVAS_PLACE) },
    )
}
