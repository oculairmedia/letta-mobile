package com.letta.mobile.data.canvas

/**
 * A rule a canvas scene must keep for every app to draw it as its writers meant
 * (letta-mobile-qygvv.30). [wire] is how a refusal names it to a model and a WARN names it in the
 * logs; [breaksRender] marks the ones that stop an app drawing the board at all, as opposed to a
 * reference the renderer quietly ignores (DrawBox drops a binding to a shape that is gone).
 */
enum class CanvasStateInvariant(val wire: String, val breaksRender: Boolean = false) {
    SCENE_PARSES("scene.parses", breaksRender = true),
    SCENE_SIZE("scene.size"),
    SCENE_ELEMENT_COUNT("scene.elementCount"),
    ELEMENT_SHAPE("element.shape"),
    ELEMENT_EXISTS("element.exists"),
    ELEMENT_DUPLICATE_ID("element.duplicateId", breaksRender = true),
    ELEMENT_DECODES("element.decodes"),
    ELEMENT_SIZE("element.size"),
    ELEMENT_BINDING("element.binding"),
    DOCUMENT_EXISTS("document.exists"),
    DOCUMENT_DECODES("document.decodes"),
    DOCUMENT_SIZE("document.size"),
    LABEL_OWNER("label.owner"),
    ARROW_BINDING("arrow.binding"),
}

/**
 * One broken rule: [invariant] on [subject] (an element or document id, or `scene`), with what is
 * wrong in [detail]. Two violations are the same one when their invariant and subject are, so a
 * batch is measured by the violations it adds, not by the ones the board already had.
 */
data class CanvasStateViolation(val invariant: CanvasStateInvariant, val subject: String, val detail: String) {
    val key: Pair<CanvasStateInvariant, String> get() = invariant to subject

    override fun toString(): String = "${invariant.wire} on '$subject': $detail"
}

/** How large a scene may grow before a write that grows it further is refused. */
object CanvasSceneLimits {
    const val MAX_SCENE_CHARS: Int = 8 * 1024 * 1024
    const val MAX_ELEMENTS: Int = 5_000
    const val MAX_ELEMENT_CHARS: Int = 512 * 1024
    const val MAX_DOCUMENT_CHARS: Int = 512 * 1024
}

/**
 * Everything wrong with a projected scene (op-log bookkeeping included): what the host refuses to
 * let a batch introduce, and what an app checks before it draws a synced scene.
 */
object CanvasSceneState {
    const val SCENE = "scene"

    fun violations(sceneJson: String): List<CanvasStateViolation> {
        val index = CanvasSceneIndex.of(sceneJson) ?: return listOf(unparsed)
        return CanvasSceneStateChecks.all(index, sceneJson.length)
    }

    private val unparsed = CanvasStateViolation(
        CanvasStateInvariant.SCENE_PARSES, SCENE, "is not a JSON object with an \"elements\" array",
    )
}
