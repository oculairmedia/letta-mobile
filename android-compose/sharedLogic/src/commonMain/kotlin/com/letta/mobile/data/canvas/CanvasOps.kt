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
     * Upserts a block document (a Cascade editor JSON document) attached to the canvas. Documents
     * live on the board beside the drawing, keyed by [documentId]; last writer wins per document.
     * A null [frame] keeps the document where it already is.
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
    is CanvasOp.BatchOp -> copy(actorId = actorId, ops = ops.map { it.withActor(actorId) })
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
