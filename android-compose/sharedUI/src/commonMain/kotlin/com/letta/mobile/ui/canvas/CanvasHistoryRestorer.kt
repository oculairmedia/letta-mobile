package com.letta.mobile.ui.canvas

import com.letta.mobile.data.canvas.CanvasCheckpoint
import com.letta.mobile.data.canvas.CanvasDeletedElement
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.storage.AssetStore
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CanvasHistoryRestorer {

    data class WorkspaceContext(
        val session: CanvasSession?,
        val assets: AssetStore?,
        val controller: DrawBoxController,
        val history: CanvasHistory,
    )

    data class RestoredSceneState(
        val sceneJson: String,
        val cleanDrawing: String,
        val elements: List<Element>,
        val deletedHistory: List<CanvasDeletedElement> = emptyList(),
        val message: String,
    )

    suspend fun restoreElement(
        context: WorkspaceContext,
        element: CanvasDeletedElement,
        onSuccess: (RestoredSceneState) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val session = context.session ?: return
        val result = runCatching { session.restoreDeletedElement(element) }
        val restored = result.getOrNull()
        if (restored == null) {
            onFailure(result.exceptionOrNull()?.message ?: "Item no longer available")
            return
        }
        val clean = CanvasOpProjector.stripMetadataForDrawBox(restored.sceneJson)
        val parsed = withContext(Dispatchers.Default) { CanvasImageAssets.parse(clean, context.assets) }
        if (parsed != null) context.controller.importExternal(parsed) else context.controller.importExternal(clean)
        context.history.clear()
        val remainingDeleted = session.deletedElements()
        onSuccess(
            RestoredSceneState(
                sceneJson = restored.sceneJson,
                cleanDrawing = clean,
                elements = context.controller.state.value.elements,
                deletedHistory = remainingDeleted,
                message = "Restored drawing item",
            ),
        )
    }

    suspend fun restoreCheckpoint(
        context: WorkspaceContext,
        checkpoint: CanvasCheckpoint,
        onSuccess: (RestoredSceneState) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val session = context.session ?: return
        val outcome = runCatching { session.restoreCheckpoint(checkpoint.checkpointId) }
        val restored = outcome.getOrNull()
        if (restored == null) {
            onFailure(outcome.exceptionOrNull()?.message ?: "Checkpoint restore failed")
            return
        }
        val clean = CanvasOpProjector.stripMetadataForDrawBox(restored.sceneJson)
        val parsed = withContext(Dispatchers.Default) { CanvasImageAssets.parse(clean, context.assets) }
        if (parsed != null) context.controller.importPath(parsed) else context.controller.importPath(clean)
        context.history.clear()
        onSuccess(
            RestoredSceneState(
                sceneJson = restored.sceneJson,
                cleanDrawing = clean,
                elements = context.controller.state.value.elements,
                message = "Restored to revision ${checkpoint.revision}",
            ),
        )
    }
}
