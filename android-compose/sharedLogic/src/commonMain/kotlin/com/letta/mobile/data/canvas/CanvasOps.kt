package com.letta.mobile.data.canvas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain operations for Canvas mutations.
 *
 * Each operation carries a unique [opId], the originating [actorId] (agent ID or user ID),
 * and a logical [lamport] timestamp for ordering in multiplayer and sync phases.
 */
@Serializable
sealed interface CanvasOp {
    val opId: String
    val actorId: String
    val lamport: Long

    @Serializable
    @SerialName("replace_scene")
    data class ReplaceSceneOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val sceneJson: String,
    ) : CanvasOp

    @Serializable
    @SerialName("add_element")
    data class AddElementOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val elementId: String,
        val elementJson: String,
    ) : CanvasOp

    @Serializable
    @SerialName("update_element")
    data class UpdateElementOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val elementId: String,
        val elementJson: String,
    ) : CanvasOp

    @Serializable
    @SerialName("remove_element")
    data class RemoveElementOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val elementId: String,
    ) : CanvasOp

    @Serializable
    @SerialName("set_background")
    data class SetBackgroundOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val colorHex: String,
    ) : CanvasOp

    /**
     * Binds (or, with both ends null, unbinds) a connector element's ends to block documents.
     * Bindings to drawn shapes are DrawBox's own, on the element; this covers what DrawBox does
     * not know about. Last writer wins per connector.
     */
    @Serializable
    @SerialName("set_arrow_binding")
    data class SetArrowBindingOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val elementId: String,
        val binding: CanvasArrowBinding,
    ) : CanvasOp

    /**
     * Marks a block document as the label OWNED by [shapeId], or releases it when [shapeId] is
     * null.
     *
     * Ownership is recorded rather than inferred from the document's id. A board can hold a
     * perfectly ordinary note called `label-report`, and treating the name as proof of ownership
     * means the label reconciler deletes it the moment no shape by that name exists.
     */
    @Serializable
    @SerialName("set_label_owner")
    data class SetLabelOwnerOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val documentId: String,
        val shapeId: String?,
    ) : CanvasOp

    /** Sets the board's background pattern (kind, spacing, colour); scene-level, last writer wins. */
    @Serializable
    @SerialName("set_background_pattern")
    data class SetBackgroundPatternOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val pattern: CanvasBackgroundPattern,
    ) : CanvasOp

    /**
     * Upserts a block document (a Cascade editor JSON document) attached to the canvas. Documents
     * live on the board beside the drawing, keyed by [documentId]; last writer wins per document.
     * A null [frame] keeps the document where it already is, a null [color] keeps its colour and a
     * null [style] keeps how its text is set.
     */
    @Serializable
    @SerialName("set_document")
    data class SetDocumentOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val documentId: String,
        val documentJson: String,
        val frame: CanvasDocumentFrame? = null,
        val color: String? = null,
        val style: CanvasTextStyle? = null,
    ) : CanvasOp

    @Serializable
    @SerialName("remove_document")
    data class RemoveDocumentOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val documentId: String,
    ) : CanvasOp

    @Serializable
    @SerialName("batch")
    data class BatchOp(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val ops: List<CanvasOp>,
    ) : CanvasOp
}

/**
 * The same operation attributed to [actorId], recursing into a [CanvasOp.BatchOp]. An
 * externally supplied op names whoever the model chose; before it is validated, logged,
 * broadcast or stamped into scene provenance it is rebound to the authenticated caller.
 */
fun CanvasOp.withActor(actorId: String): CanvasOp = when (this) {
    is CanvasOp.ReplaceSceneOp -> copy(actorId = actorId)
    is CanvasOp.AddElementOp -> copy(actorId = actorId)
    is CanvasOp.UpdateElementOp -> copy(actorId = actorId)
    is CanvasOp.RemoveElementOp -> copy(actorId = actorId)
    is CanvasOp.SetBackgroundOp -> copy(actorId = actorId)
    is CanvasOp.SetBackgroundPatternOp -> copy(actorId = actorId)
    is CanvasOp.SetArrowBindingOp -> copy(actorId = actorId)
    is CanvasOp.SetLabelOwnerOp -> copy(actorId = actorId)
    is CanvasOp.SetDocumentOp -> copy(actorId = actorId)
    is CanvasOp.RemoveDocumentOp -> copy(actorId = actorId)
    is CanvasOp.BatchOp -> copy(actorId = actorId, ops = ops.map { it.withActor(actorId) })
}

/**
 * The same operation with a fresh identity and [lamport].
 *
 * An inverse is built when a change happens and applied whenever the person presses undo, so it
 * cannot carry the clock it was born with: the scene has moved on, and last-writer-wins would
 * discard a stale op without a word. It is stamped at the moment it is applied instead.
 */
fun CanvasOp.withStamp(opId: String, lamport: Long): CanvasOp = when (this) {
    is CanvasOp.ReplaceSceneOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.AddElementOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.UpdateElementOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.RemoveElementOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.SetBackgroundOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.SetBackgroundPatternOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.SetArrowBindingOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.SetLabelOwnerOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.SetDocumentOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.RemoveDocumentOp -> copy(opId = opId, lamport = lamport)
    is CanvasOp.BatchOp -> copy(opId = opId, lamport = lamport, ops = ops.map { it.withStamp(opId, lamport) })
}

/**
 * Tool payload DTOs for App Server external tools (canvas.*).
 */
@Serializable
data class CanvasCreateArgs(
    val title: String? = null,
    @SerialName("conversation_id")
    val conversationId: String? = null,
    @SerialName("agent_id")
    val agentId: String? = null,
)

@Serializable
data class CanvasCreateResult(
    @SerialName("canvas_id")
    val canvasId: String,
)

@Serializable
data class CanvasGetSceneArgs(
    @SerialName("canvas_id")
    val canvasId: String,
)

@Serializable
data class CanvasGetSceneResult(
    @SerialName("scene_json")
    val sceneJson: String,
    val revision: Long,
)

@Serializable
data class CanvasReplaceSceneArgs(
    @SerialName("canvas_id")
    val canvasId: String,
    @SerialName("scene_json")
    val sceneJson: String,
)

@Serializable
data class CanvasReplaceSceneResult(
    val ok: Boolean,
    val revision: Long,
)

@Serializable
data class CanvasApplyOpsArgs(
    @SerialName("canvas_id")
    val canvasId: String,
    val ops: List<CanvasOp>,
)

@Serializable
data class CanvasApplyOpsResult(
    val ok: Boolean,
    val revision: Long,
)

@Serializable
data class CanvasExportSvgArgs(
    @SerialName("canvas_id")
    val canvasId: String,
)

@Serializable
data class CanvasExportSvgResult(
    val svg: String,
)

@Serializable
data class CanvasListArgs(
    @SerialName("conversation_id")
    val conversationId: String? = null,
    @SerialName("agent_id")
    val agentId: String? = null,
)

@Serializable
data class CanvasListResult(
    val ids: List<String>,
)
