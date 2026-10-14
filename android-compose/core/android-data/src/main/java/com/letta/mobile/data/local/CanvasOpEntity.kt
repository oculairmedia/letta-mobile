package com.letta.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import kotlinx.serialization.json.Json

private val opSerializer = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Entity(
    tableName = "canvas_ops",
    indices = [
        Index(value = ["canvasId", "lamport"]),
        Index(value = ["canvasId"]),
    ],
)
data class CanvasOpEntity(
    @PrimaryKey val opId: String,
    val canvasId: String,
    val lamport: Long,
    val actorId: String,
    val opType: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    /**
     * Whether the stored canvas document holds this op (letta-mobile-by4vm.22): appended unsettled,
     * settled once the document write carrying it succeeded. Rows from before the journal are
     * settled (the migration's default), since nothing records which of them the document missed.
     */
    @ColumnInfo(defaultValue = "1")
    val settled: Boolean = true,
) {
    fun toCanvasOp(): CanvasOp =
        opSerializer.decodeFromString(CanvasOp.serializer(), payloadJson)

    companion object {
        fun fromCanvasOp(
            canvasId: CanvasId,
            op: CanvasOp,
            clock: () -> Long = { System.currentTimeMillis() },
        ): CanvasOpEntity {
            val opType = when (op) {
                is CanvasOp.ReplaceSceneOp -> "replace_scene"
                is CanvasOp.AddElementOp -> "add_element"
                is CanvasOp.UpdateElementOp -> "update_element"
                is CanvasOp.RemoveElementOp -> "remove_element"
                is CanvasOp.SetBackgroundOp -> "set_background"
                is CanvasOp.SetBackgroundPatternOp -> "set_background_pattern"
                is CanvasOp.SetArrowBindingOp -> "set_arrow_binding"
                is CanvasOp.SetLabelOwnerOp -> "set_label_owner"
                is CanvasOp.SetDocumentOp -> "set_document"
                is CanvasOp.RemoveDocumentOp -> "remove_document"
                is CanvasOp.SetPluginElementOp -> "set_plugin_element"
                is CanvasOp.RemovePluginElementOp -> "remove_plugin_element"
                is CanvasOp.BatchOp -> "batch"
            }
            return CanvasOpEntity(
                opId = op.opId,
                canvasId = canvasId.value,
                lamport = op.lamport,
                actorId = op.actorId,
                opType = opType,
                payloadJson = opSerializer.encodeToString(CanvasOp.serializer(), op),
                createdAtEpochMs = clock(),
                settled = false,
            )
        }
    }
}
