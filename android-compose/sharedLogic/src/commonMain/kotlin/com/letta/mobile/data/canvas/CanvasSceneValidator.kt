package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Why one element (or the scene around it, with no [elementId]) cannot be drawn, and the type to show instead. */
data class CanvasElementProblem(val elementId: String?, val reason: String, val suggested: CanvasElementSpec)

/** A writer's scene or element as the apps will get it: normalised JSON, or what stops them drawing it. */
sealed interface CanvasSceneCheck {
    data class Valid(val json: String) : CanvasSceneCheck

    data class Invalid(val problems: List<CanvasElementProblem>) : CanvasSceneCheck {
        val message: String get() = CanvasSceneSchemaText.problems(problems)
    }
}

/** A batch of a writer's ops: every scene and element in it drawable (and normalised), or not. */
sealed interface CanvasOpsCheck {
    data class Valid(val ops: List<CanvasOp>) : CanvasOpsCheck

    data class Invalid(val message: String) : CanvasOpsCheck
}

/**
 * Holds a writer's scenes and elements to [CanvasSceneSchema] before they enter the log
 * (letta-mobile-qygvv.21). An element the apps cannot draw is refused with its id, what is wrong
 * and an example of the type it was probably meant to be; nothing of a refused batch is published.
 *
 * What a writer may leave out is filled in ([CanvasElementSpec.defaults], a missing `bgColor`),
 * and a `#rrggbb` colour gets its alpha, so what is published is what DrawBox's decoder requires.
 */
object CanvasSceneValidator {
    private val json = Json { prettyPrint = false }

    fun scene(sceneJson: String): CanvasSceneCheck {
        val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject
            ?: return sceneProblem("scene_json is not a JSON object")
        val elements = root["elements"] as? JsonArray ?: return sceneProblem("scene has no \"elements\" array")
        val background = CanvasSceneColors.background(root["bgColor"]) ?: return sceneProblem("bgColor is not a colour \"#rrggbbaa\"")
        val checked = elements.map { checkElement(it, idOverride = null) }
        val problems = checked.mapNotNull { it.problem }
        if (problems.isNotEmpty()) return CanvasSceneCheck.Invalid(problems)
        val normalised = JsonObject(root + mapOf("bgColor" to background, "elements" to JsonArray(checked.mapNotNull { it.element })))
        return CanvasSceneCheck.Valid(encode(normalised))
    }

    /** An element op's [elementJson]; its id is the op's [elementId], whatever the JSON says. */
    fun element(elementId: String, elementJson: String): CanvasSceneCheck {
        val parsed = runCatching { json.parseToJsonElement(elementJson) }.getOrNull() ?: JsonNull
        val checked = checkElement(parsed, idOverride = elementId)
        return checked.element?.let { CanvasSceneCheck.Valid(encode(it)) }
            ?: CanvasSceneCheck.Invalid(listOfNotNull(checked.problem))
    }

    fun ops(ops: List<CanvasOp>): CanvasOpsCheck {
        val problems = mutableListOf<CanvasElementProblem>()
        val checked = ops.map { checkOp(it, problems) }
        return if (problems.isEmpty()) CanvasOpsCheck.Valid(checked) else CanvasOpsCheck.Invalid(CanvasSceneSchemaText.problems(problems))
    }

    private fun checkOp(op: CanvasOp, problems: MutableList<CanvasElementProblem>): CanvasOp = when (op) {
        is CanvasOp.ReplaceSceneOp -> scene(op.sceneJson).orRecord(problems, op) { op.copy(sceneJson = it) }
        is CanvasOp.AddElementOp -> element(op.elementId, op.elementJson).orRecord(problems, op) { op.copy(elementJson = it) }
        is CanvasOp.UpdateElementOp -> element(op.elementId, op.elementJson).orRecord(problems, op) { op.copy(elementJson = it) }
        is CanvasOp.BatchOp -> op.copy(ops = op.ops.map { checkOp(it, problems) })
        else -> op
    }

    private inline fun CanvasSceneCheck.orRecord(
        problems: MutableList<CanvasElementProblem>,
        original: CanvasOp,
        rewrite: (String) -> CanvasOp,
    ): CanvasOp = when (this) {
        is CanvasSceneCheck.Valid -> rewrite(json)
        is CanvasSceneCheck.Invalid -> original.also { problems += this.problems }
    }

    private class CheckedElement(val element: JsonObject?, val problem: CanvasElementProblem?)

    private fun checkElement(raw: JsonElement, idOverride: String?): CheckedElement {
        val obj = raw as? JsonObject
            ?: return CanvasElementProblem(idOverride, "is not a JSON object", CanvasSceneSchema.shape).failure()
        val id = idOverride ?: obj["id"].stringValue()
        if (id.isNullOrBlank()) {
            return CanvasElementProblem(null, "has no string \"id\"", CanvasSceneSchema.closestType(obj["type"].stringValue().orEmpty())).failure()
        }
        val typeName = obj["type"].stringValue() ?: return CanvasElementProblem(id, "has no string \"type\"", CanvasSceneSchema.shape).failure()
        val spec = CanvasSceneSchema.spec(typeName)
            ?: return CanvasElementProblem(id, "has unknown type '$typeName'", CanvasSceneSchema.closestType(typeName)).failure()
        val filled = withDefaults(JsonObject(obj + ("id" to JsonPrimitive(id))), spec)
        val fault = CanvasSceneElementFaults.of(filled, spec) ?: return CheckedElement(filled, null)
        return CanvasElementProblem(id, "(${spec.type}) $fault", spec).failure()
    }

    /** [obj] with nulls dropped, colours given their alpha, [spec]'s defaults for what it left out, and its type's own name. */
    private fun withDefaults(obj: JsonObject, spec: CanvasElementSpec): JsonObject = buildJsonObject {
        obj.forEach { (key, value) -> if (value !is JsonNull) put(key, CanvasSceneColors.withAlpha(key, value)) }
        spec.defaults.forEach { (key, value) -> if (obj[key] == null || obj[key] is JsonNull) put(key, value) }
        put("type", JsonPrimitive(spec.type))
    }

    private fun CanvasElementProblem.failure() = CheckedElement(null, this)

    private fun sceneProblem(reason: String) =
        CanvasSceneCheck.Invalid(listOf(CanvasElementProblem(null, reason, CanvasSceneSchema.shape)))


    private fun encode(value: JsonObject): String = json.encodeToString(JsonObject.serializer(), value)
}
