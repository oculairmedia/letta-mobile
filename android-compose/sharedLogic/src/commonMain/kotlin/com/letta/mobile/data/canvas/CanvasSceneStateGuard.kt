package com.letta.mobile.data.canvas

import com.letta.mobile.util.attr
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What one open board draws, given each scene its session projects.
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
class CanvasSceneStateGuard(private val canvasId: CanvasId?) {
    private var lastValid: JsonObject? = null
    private var reported: Set<Pair<CanvasStateInvariant, String>> = emptySet()

    /** What to hand DrawBox for [sceneJson], the session's projected scene (op-log bookkeeping included). */
    fun drawable(sceneJson: String): String {
        val root = CanvasOpProjector.parseSceneOrNull(sceneJson)
        if (root != null) return CanvasOpProjector.encodeScene(drawable(root, sceneJson.length))
        report(CanvasSceneState.violations(sceneJson))
        return lastValid?.let(CanvasOpProjector::encodeScene) ?: sceneJson
    }

    /**
     * [drawable] for a scene already parsed, [sceneChars] long as stored. A board brings in a synced
     * scene from this one tree: checked, stripped and guarded with no further parse.
     */
    fun drawable(root: JsonObject, sceneChars: Int): JsonObject {
        val violations = CanvasSceneState.violations(root, sceneChars)
        report(violations)
        val stripped = CanvasOpProjector.stripMetadataForDrawBox(root) ?: return lastValid ?: root
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
            "canvasId" attr canvasId?.value,
            "invariant" attr first.invariant.wire,
            "subject" attr first.subject,
            "detail" attr first.detail,
            "violations" attr violations.size,
            "invariants" attr violations.map { it.invariant.wire }.distinct().joinToString(","),
            "fallback" attr violations.any { it.invariant.breaksRender },
            level = Telemetry.Level.WARN,
        )
    }

    private fun withoutDuplicates(root: JsonObject): JsonObject {
        val elements = root["elements"] as? JsonArray ?: return root
        val lastById = elements.withIndex().associateBy { (_, element) -> (element as? JsonObject)?.get("id")?.toString() ?: "" }
        val kept = elements.filterIndexed { position, element ->
            val id = (element as? JsonObject)?.get("id")?.toString() ?: return@filterIndexed true
            lastById[id]?.index == position
        }
        return JsonObject(root + ("elements" to JsonArray(kept)))
    }
}
