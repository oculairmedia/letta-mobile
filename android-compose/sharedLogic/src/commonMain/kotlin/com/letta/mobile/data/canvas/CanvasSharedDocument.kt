package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.DocumentAcl
import com.letta.mobile.data.document.DocumentReducer
import com.letta.mobile.data.document.HistoryBudget
import com.letta.mobile.data.document.OpArchive
import com.letta.mobile.data.document.SharedDocument
import kotlinx.serialization.json.Json

/**
 * The canvas as a [SharedDocument] kind: a board folds its ops exactly as [CanvasSession] does
 * ([CanvasOpProjector.project] on the stored scene, one revision per batch), so a board opened
 * through the primitive and one opened through the session write the same scene.
 */
object CanvasDocumentReducer : DocumentReducer<CanvasOp, CanvasDocument> {
    private val json = Json { encodeDefaults = true }

    override fun reduce(snapshot: CanvasDocument, ops: List<CanvasOp>, nowEpochMs: Long): CanvasDocument =
        snapshot.copy(
            revision = snapshot.revision + 1L,
            sceneJson = CanvasOpProjector.project(snapshot.sceneJson, ops),
            updatedAtEpochMs = nowEpochMs,
        )

    override fun sizeOf(op: CanvasOp): Long = json.encodeToString(CanvasOp.serializer(), op).length.toLong()

    override fun aclOf(snapshot: CanvasDocument): DocumentAcl? = snapshot.acl

    override fun lamportOf(snapshot: CanvasDocument): Long = CanvasOpProjector.maxLamport(snapshot.sceneJson)
}

/** A board opened as a [SharedDocument]. */
typealias SharedCanvasDocument = SharedDocument<CanvasId, CanvasOp, CanvasDocument>

/**
 * Opens board [canvasId] as a [SharedDocument] over the canvas's own stores - in the apps, the
 * Automerge notebook repository ([NotebookCanvasDocumentStore]) and the canvas op log - so the
 * persistence format is the one every board already has. [create] makes the board when the store
 * has none.
 *
 * The open board's [CanvasSession] still writes through those stores directly; folding its write
 * path into the primitive is a follow-up that waits on the notebook write-path work landing.
 */
@Suppress("LongParameterList")
suspend fun openCanvasDocument(
    canvasId: CanvasId,
    store: CanvasDocumentStore,
    opLog: CanvasOpLog,
    create: () -> CanvasDocument = { CanvasDocument(id = canvasId, title = "Canvas") },
    budget: HistoryBudget = HistoryBudget(),
    archive: OpArchive<CanvasId, CanvasOp, CanvasDocument>? = null,
    clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
): SharedCanvasDocument = SharedDocument.open(
    id = canvasId,
    create = create,
    reducer = CanvasDocumentReducer,
    store = store,
    opLog = opLog,
    budget = budget,
    archive = archive,
    clock = clock,
    tag = "CanvasDocument",
)
