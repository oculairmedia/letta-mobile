package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.CanvasSceneIndex.Companion.idOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The invariants of [CanvasSceneState], one small check each. */
internal object CanvasSceneStateChecks {
    private val json = Json { ignoreUnknownKeys = true }

    fun all(index: CanvasSceneIndex, sceneChars: Int): List<CanvasStateViolation> =
        size(index, sceneChars) + elements(index) + duplicates(index) + elementBindings(index) +
            documents(index) + labelOwners(index) + arrowBindings(index)

    private fun size(index: CanvasSceneIndex, sceneChars: Int): List<CanvasStateViolation> = listOfNotNull(
        violation(CanvasStateInvariant.SCENE_SIZE, CanvasSceneState.SCENE, "is $sceneChars chars, over ${CanvasSceneLimits.MAX_SCENE_CHARS}")
            .takeIf { sceneChars > CanvasSceneLimits.MAX_SCENE_CHARS },
        violation(CanvasStateInvariant.SCENE_ELEMENT_COUNT, CanvasSceneState.SCENE, "has ${index.elements.size} elements, over ${CanvasSceneLimits.MAX_ELEMENTS}")
            .takeIf { index.elements.size > CanvasSceneLimits.MAX_ELEMENTS },
    )

    private fun elements(index: CanvasSceneIndex): List<CanvasStateViolation> =
        index.elements.withIndex().flatMap { (position, element) -> element(position, element) }

    private fun element(position: Int, element: JsonObject?): List<CanvasStateViolation> {
        val subject = element?.idOrNull() ?: "elements[$position]"
        if (element == null) return listOf(violation(CanvasStateInvariant.ELEMENT_DECODES, subject, "is not a JSON object"))
        val chars = element.toString().length
        return listOfNotNull(
            CanvasSceneElementFaults.undecodable(element)?.let { violation(CanvasStateInvariant.ELEMENT_DECODES, subject, it) },
            violation(CanvasStateInvariant.ELEMENT_SIZE, subject, "is $chars chars, over ${CanvasSceneLimits.MAX_ELEMENT_CHARS}")
                .takeIf { chars > CanvasSceneLimits.MAX_ELEMENT_CHARS },
        )
    }

    private fun duplicates(index: CanvasSceneIndex): List<CanvasStateViolation> =
        index.elements.mapNotNull { it?.idOrNull() }.groupingBy { it }.eachCount().filterValues { it > 1 }
            .map { (id, count) -> violation(CanvasStateInvariant.ELEMENT_DUPLICATE_ID, id, "is the id of $count elements") }

    private fun elementBindings(index: CanvasSceneIndex): List<CanvasStateViolation> = index.elements.filterNotNull().flatMap { element ->
        val id = element.idOrNull() ?: return@flatMap emptyList()
        BINDING_FIELDS.mapNotNull { field ->
            val target = (element[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            if (target == null || index.hasElement(target)) null
            else violation(CanvasStateInvariant.ELEMENT_BINDING, id, "$field names '$target', which is not on the canvas")
        }
    }

    private fun documents(index: CanvasSceneIndex): List<CanvasStateViolation> = index.documents.flatMap { document ->
        listOfNotNull(
            violation(CanvasStateInvariant.DOCUMENT_DECODES, document.id, "documentJson is not a JSON object with a \"blocks\" array")
                .takeIf { !decodes(document.json) },
            violation(CanvasStateInvariant.DOCUMENT_SIZE, document.id, "is ${document.json.length} chars, over ${CanvasSceneLimits.MAX_DOCUMENT_CHARS}")
                .takeIf { document.json.length > CanvasSceneLimits.MAX_DOCUMENT_CHARS },
        )
    }

    /** A block document the editor can open: an object whose `blocks` is an array (empty for a new note). */
    fun decodes(documentJson: String): Boolean {
        val root = runCatching { json.parseToJsonElement(documentJson) }.getOrNull() as? JsonObject ?: return false
        return root["blocks"] is JsonArray
    }

    private fun labelOwners(index: CanvasSceneIndex): List<CanvasStateViolation> =
        index.labelOwners.filterKeys(index::hasDocument).filterValues { !index.hasElement(it) }
            .map { (documentId, shapeId) ->
                violation(CanvasStateInvariant.LABEL_OWNER, documentId, "is the label of shape '$shapeId', which is not on the canvas")
            }

    private fun arrowBindings(index: CanvasSceneIndex): List<CanvasStateViolation> =
        index.arrowBindings.filterKeys(index::hasElement).flatMap { (connectorId, binding) ->
            listOfNotNull(binding.start?.let { "start" to it }, binding.end?.let { "end" to it })
                .filter { (_, end) -> !index.hasDocument(end.documentId) }
                .map { (side, end) ->
                    violation(CanvasStateInvariant.ARROW_BINDING, connectorId, "$side is bound to document '${end.documentId}', which is not on the canvas")
                }
        }

    private fun violation(invariant: CanvasStateInvariant, subject: String, detail: String) =
        CanvasStateViolation(invariant, subject, detail)

    private val BINDING_FIELDS = listOf("startBinding", "endBinding")
}
