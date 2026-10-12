package com.letta.mobile.data.document.markdown

import com.letta.mobile.data.document.DocumentAcl
import com.letta.mobile.data.document.DocumentBackend
import com.letta.mobile.data.document.DocumentOp
import com.letta.mobile.data.document.DocumentReducer
import com.letta.mobile.data.document.DocumentSnapshot
import com.letta.mobile.data.document.DocumentStore
import com.letta.mobile.data.document.OpLog
import com.letta.mobile.data.document.OpStamp
import com.letta.mobile.data.document.PrincipalAcl
import com.letta.mobile.data.document.SharedDocument
import com.letta.mobile.data.document.SharedDocumentOptions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The second [SharedDocument] kind, proving the primitive is not the canvas in disguise: one
 * Markdown text with a title, for the Agent Tray's agent-scoped persistent panel (zxk2n.30).
 */
@Serializable
data class MarkdownDocId(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The Agent Tray of [agentId]: one per agent, the same id on every app. */
        fun agentTray(agentId: String): MarkdownDocId = MarkdownDocId("tray-agent-$agentId")
    }
}

@Serializable
data class MarkdownDocument(
    override val id: MarkdownDocId,
    val title: String = "",
    val markdown: String = "",
    /** The agent the document belongs to, when it is agent-scoped (the Agent Tray). */
    val agentId: String? = null,
    override val revision: Long = 0L,
    override val updatedAtEpochMs: Long = 0L,
    val acl: PrincipalAcl? = null,
    val titleStamp: OpStamp? = null,
    val markdownStamp: OpStamp? = null,
) : DocumentSnapshot<MarkdownDocId>

/** Ops on a [MarkdownDocument]. Each field is last-writer-wins, so replicas converge in any arrival order. */
@Serializable
sealed interface MarkdownOp : DocumentOp {
    @Serializable
    @SerialName("set_markdown")
    data class SetMarkdown(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val markdown: String,
    ) : MarkdownOp

    @Serializable
    @SerialName("set_title")
    data class SetTitle(
        override val opId: String,
        override val actorId: String,
        override val lamport: Long,
        val title: String,
    ) : MarkdownOp
}

object MarkdownReducer : DocumentReducer<MarkdownOp, MarkdownDocument> {
    private val json = Json { encodeDefaults = true }

    override fun reduce(snapshot: MarkdownDocument, ops: List<MarkdownOp>, nowEpochMs: Long): MarkdownDocument =
        ops.fold(snapshot) { doc, op -> applyOp(doc, op) }.copy(revision = snapshot.revision + 1, updatedAtEpochMs = nowEpochMs)

    private fun applyOp(doc: MarkdownDocument, op: MarkdownOp): MarkdownDocument = when (op) {
        is MarkdownOp.SetMarkdown -> if (wins(doc.markdownStamp, op)) doc.copy(markdown = op.markdown, markdownStamp = OpStamp.of(op)) else doc
        is MarkdownOp.SetTitle -> if (wins(doc.titleStamp, op)) doc.copy(title = op.title, titleStamp = OpStamp.of(op)) else doc
    }

    /** Whether [op] is the newer writer of a field last written at [stamp] (never written: always). */
    private fun wins(stamp: OpStamp?, op: MarkdownOp): Boolean = stamp?.isOlderThan(op) ?: true

    override fun sizeOf(op: MarkdownOp): Long = json.encodeToString(MarkdownOp.serializer(), op).length.toLong()

    override fun aclOf(snapshot: MarkdownDocument): DocumentAcl? = snapshot.acl

    override fun lamportOf(snapshot: MarkdownDocument): Long =
        maxOf(snapshot.titleStamp?.lamport ?: 0L, snapshot.markdownStamp?.lamport ?: 0L)
}

/** A [MarkdownDocument] opened as a [SharedDocument]. */
typealias SharedMarkdownDocument = SharedDocument<MarkdownDocId, MarkdownOp, MarkdownDocument>

/** How a Markdown document's history is bounded and reported. */
typealias MarkdownDocumentOptions = SharedDocumentOptions<MarkdownDocId, MarkdownOp, MarkdownDocument>

/** Opens Markdown document `seed.id` over [store] and [opLog], starting from [seed] when the store has none. */
suspend fun openMarkdownDocument(
    seed: MarkdownDocument,
    store: DocumentStore<MarkdownDocId, MarkdownDocument>,
    opLog: OpLog<MarkdownDocId, MarkdownOp>,
    options: MarkdownDocumentOptions = MarkdownDocumentOptions(tag = "MarkdownDocument"),
): SharedMarkdownDocument = SharedDocument.open(seed, DocumentBackend(MarkdownReducer, store, opLog), options)