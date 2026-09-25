package com.letta.mobile.data.canvas

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What one open board draws, given each scene its session projects (letta-mobile-qygvv.30).
 *
 * A scene already in the log can break the rules the host now holds writes to ([CanvasSceneState]):
 * it was logged before the check existed, or came from a peer that does not check. The board must
 * neither crash nor go blank on it, and the ops stay in the log untouched; only what is drawn is
 * decided here.
 *
 * - Every broken rule is reported once as a WARN `Canvas/scene.invalidState`, naming the rule.
 * - A rule that stops the board drawing ([CanvasStateInvariant.breaksRender]: the scene does not
 *   parse, two elements share an id) keeps the last scene that drew; opened on such a scene, the
 *   board draws it with the duplicates dropped (the last writer of each id kept).
 * - Any other (a dangling label owner or binding, a note that does not decode) is drawn as it is:
 *   the renderer already ignores such a reference, and holding back every later change for it would
 *   freeze the board. Elements DrawBox cannot decode are dropped by [CanvasSceneRenderGuard].
 *
 * One guard per open board; not thread-safe, it is called from the board's one loader.
 */
class CanvasSceneStateGuard(private val canvasId: String?) {
    private var lastValid: String? = null
    private var reported: Set<Pair<CanvasStateInvariant, String>> = emptySet()

    /** What to hand DrawBox for [sceneJson], the session's projected scene (op-log bookkeeping included). */
    fun drawable(sceneJson: String): String {
        val violations = if (sceneJson.isBlank()) emptyList() else CanvasSceneState.violations(sceneJson)
        report(violations)
        val stripped = CanvasOpProjector.stripMetadataForDrawBox(sceneJson)
        if (violations.none { it.invariant.breaksRender }) {
            return CanvasSceneRenderGuard.renderable(stripped, canvasId).also { lastValid = it }
        }
        return lastValid ?: CanvasSceneRenderGuard.renderable(withoutDuplicates(stripped), canvasId)
    }

    private fun report(violations: List<CanvasStateViolation>) {
        val keys = violations.map { it.key }.toSet()
        if (keys == reported) return
        reported = keys
        val first = violations.firstOrNull() ?: return
        Telemetry.event(
            "Canvas", "scene.invalidState",
            "canvasId" to canvasId,
            "invariant" to first.invariant.wire,
            "subject" to first.subject,
            "detail" to first.detail,
            "violations" to violations.size,
            "invariants" to violations.map { it.invariant.wire }.distinct().joinToString(","),
            "fallback" to violations.any { it.invariant.breaksRender },
            level = Telemetry.Level.WARN,
        )
    }

    private fun withoutDuplicates(stripped: String): String {
        val root = runCatching { json.parseToJsonElement(stripped) }.getOrNull() as? JsonObject ?: return stripped
        val elements = root["elements"] as? JsonArray ?: return stripped
        val lastById = elements.withIndex().associateBy { (_, element) -> (element as? JsonObject)?.get("id")?.toString() ?: "" }
        val kept = elements.filterIndexed { position, element ->
            val id = (element as? JsonObject)?.get("id")?.toString() ?: return@filterIndexed true
            lastById[id]?.index == position
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("elements" to JsonArray(kept))))
    }

    private companion object {
        val json = Json { prettyPrint = false }
    }
}
