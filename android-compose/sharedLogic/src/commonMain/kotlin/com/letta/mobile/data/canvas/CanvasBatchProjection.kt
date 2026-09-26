package com.letta.mobile.data.canvas

/**
 * A batch applied step by step to a scratch copy of [baseSceneJson] (nothing is published), and
 * every rule the result breaks that the base did not, each pinned to the op that broke it.
 */
internal class CanvasBatchProjection(private val baseSceneJson: String, private val steps: List<CanvasBatchStep>) {
    /** The scene before each step, then the scene the batch leaves: `steps.size + 1` of them. */
    private val scenes: List<String> = steps.runningFold(baseSceneJson) { scene, step -> CanvasOpProjector.project(scene, listOf(step.scratch)) }

    val result: String get() = scenes.last()

    fun violations(): List<CanvasBatchViolation> = (opReferences() + introduced()).distinctBy { it.opIndex to it.violation.key }

    /** What each op names that is not there: before it applies, or once the whole batch has. */
    private fun opReferences(): List<CanvasBatchViolation> {
        val final = indexOf(result)
        return steps.withIndex().flatMap { (position, step) ->
            listOfNotNull(CanvasOpReferences.before(step.op, indexOf(scenes[position])), CanvasOpReferences.after(step.op, final))
                .map { step.refusal(it) }
        }
    }

    /**
     * The result's violations the base did not have. Each is blamed on the op after which it holds
     * for good: a batch may pass through a broken state (remove a shape, then its label) and end
     * sound, and that is not refused.
     */
    private fun introduced(): List<CanvasBatchViolation> {
        val before = CanvasSceneState.violations(baseSceneJson).map { it.key }.toSet()
        val added = CanvasSceneState.violations(result).filter { it.key !in before }
        if (added.isEmpty()) return emptyList()
        val keysAt = scenes.map { scene -> CanvasSceneState.violations(scene).map { it.key }.toSet() }
        return added.map { violation ->
            val lastSound = keysAt.indexOfLast { violation.key !in it }
            steps[lastSound.coerceIn(0, steps.lastIndex)].refusal(violation)
        }
    }

    private fun CanvasBatchStep.refusal(violation: CanvasStateViolation) =
        CanvasBatchViolation(label, CanvasBatchSteps.describe(op), violation)

    private fun indexOf(scene: String): CanvasSceneIndex = CanvasSceneIndex.of(scene) ?: EMPTY

    private companion object {
        val EMPTY: CanvasSceneIndex = CanvasSceneIndex.of("")!!
    }
}
