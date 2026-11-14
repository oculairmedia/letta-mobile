package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import com.letta.mobile.util.runCatchingCancellable
import java.nio.file.Path

/**
 * The only entry point to canvas mount persistence from outside this package. Every call runs the same
 * fixed sequence, and each step happens strictly before the next:
 *
 * 1. Input bounds: every id must be non-blank and at most [MAX_ID_BYTES] UTF-8 bytes, else
 *    [CanvasMountResult.InvalidInput]. Neither the authority nor storage is consulted.
 * 2. Scope: [CanvasMountAuthority.mayAddress], before storage is touched, so a denied caller gets the
 *    same [CanvasMountResult.Denied] whether or not a record exists.
 * 3. Proposal (mutations only): [CanvasMountAuthority.mayPropose] for the canvas the caller would store,
 *    before any mutation; a denied proposal never reaches storage. A removal ([clearDefault]) proposes no
 *    canvas and asks [CanvasMountAuthority.mayClear] instead.
 * 4. Storage. A failure before anything changed becomes [CanvasMountResult.StorageFailure]; a mutation
 *    failure at or after its commit point becomes [CanvasMountResult.OutcomeUnknown], and the caller must
 *    read back. Cancellation before the commit point propagates; once the record was replaced the store
 *    still returns its outcome (see [FileCanvasMountStore]).
 * 5. Winner (read and bind only): [CanvasMountAuthority.maySee] for the canvas read from storage, before
 *    it is returned. A denied winner yields [CanvasMountResult.Denied] and appears nowhere.
 *
 * Scope authorization grants knowledge of whether the addressed record exists ([CanvasMountResult.None]);
 * winner authorization governs only which canvas it holds.
 *
 * Denied winner on [bindIfAbsent]: when a record already exists the store writes nothing, so a denied
 * winner leaves storage exactly as it was. When no record exists, the caller's own proposal (already
 * authorized in step 3) is durably stored and becomes the winner; if the authority then refuses to let the
 * caller see it the result is still [CanvasMountResult.Denied], with that proposal stored.
 *
 * Replace and set return the caller's own authorized canvas and clear returns none, so step 5 does not apply to them.
 */
class AuthorizedCanvasMounts internal constructor(
    private val storage: CanvasMountStorage,
    private val authority: CanvasMountAuthority,
) {
    /** Uses the host's file-backed mount store beneath the protected [productStateDirectory]. */
    constructor(productStateDirectory: Path, hostId: HostId, authority: CanvasMountAuthority) :
        this(FileCanvasMountStore(productStateDirectory, hostId), authority)

    /** Reads the addressed binding or agent default: [CanvasMountResult.Found] or [CanvasMountResult.None]. */
    suspend fun read(request: CanvasMountRequest<CanvasMountScope>): CanvasMountResult =
        execute(request, MountStep(proposal = null, revealsStored = true, outcome = CanvasMountResult::Found) { store ->
            val stored = when (val scope = request.scope) {
                is CanvasMountScope.AgentDefault -> store.readDefault(scope.agent)
                is CanvasMountScope.Conversation -> store.readBinding(scope.agent, scope.conversation)
            }
            stored?.let { CanvasMountMutation.Applied(it, written = false) }
        })

    /** Inserts [proposed] only when absent: [CanvasMountResult.Bound] with the durable winner. */
    suspend fun bindIfAbsent(
        request: CanvasMountRequest<CanvasMountScope.Conversation>,
        proposed: CanvasId,
    ): CanvasMountResult =
        execute(request, MountStep(proposed, revealsStored = true, outcome = CanvasMountResult::Bound) { store ->
            store.bindIfAbsent(request.scope.agent, request.scope.conversation, proposed)
        })

    /** Replaces the addressed binding with [canvas]: [CanvasMountResult.Replaced]. */
    suspend fun replaceBinding(
        request: CanvasMountRequest<CanvasMountScope.Conversation>,
        canvas: CanvasId,
    ): CanvasMountResult =
        execute(request, MountStep(canvas, revealsStored = false, outcome = CanvasMountResult::Replaced) { store ->
            store.replaceBinding(request.scope.agent, request.scope.conversation, canvas)
        })

    /** Sets the addressed agent default to [canvas]: [CanvasMountResult.DefaultSet]. */
    suspend fun setDefault(
        request: CanvasMountRequest<CanvasMountScope.AgentDefault>,
        canvas: CanvasId,
    ): CanvasMountResult =
        execute(request, MountStep(canvas, revealsStored = false, outcome = CanvasMountResult::DefaultSet) { store ->
            store.setDefault(request.scope.agent, canvas)
        })

    /**
     * Removes the addressed agent default: [CanvasMountResult.DefaultCleared], whether or not one existed. Asks
     * [CanvasMountAuthority.mayClear] in place of a proposal, since no canvas is stored.
     */
    suspend fun clearDefault(request: CanvasMountRequest<CanvasMountScope.AgentDefault>): CanvasMountResult =
        execute(request, MountStep(null, revealsStored = false, outcome = { CanvasMountResult.DefaultCleared }, clears = true) { store ->
            store.clearDefault(request.scope.agent)
        })

    private suspend fun execute(request: CanvasMountRequest<CanvasMountScope>, step: MountStep): CanvasMountResult =
        refusal(request, step) ?: stored(request, step)

    private suspend fun refusal(
        request: CanvasMountRequest<CanvasMountScope>,
        step: MountStep,
    ): CanvasMountResult? = when {
        !request.isBounded() || step.proposal?.value?.let(::isBoundedMountId) == false -> CanvasMountResult.InvalidInput
        !allows { mayAddress(request) } -> CanvasMountResult.Denied
        !proposalAllowed(request, step.proposal) -> CanvasMountResult.Denied
        step.clears && !allows { mayClear(request) } -> CanvasMountResult.Denied
        else -> null
    }

    private suspend fun proposalAllowed(request: CanvasMountRequest<CanvasMountScope>, proposal: CanvasId?): Boolean =
        proposal == null || allows { mayPropose(request, proposal) }

    private suspend fun stored(request: CanvasMountRequest<CanvasMountScope>, step: MountStep): CanvasMountResult {
        val stored = runCatchingCancellable { step.action(storage) }.getOrElse { CanvasMountMutation.NotApplied(it) }
        return when (stored) {
            null -> CanvasMountResult.None
            is CanvasMountMutation.NotApplied -> CanvasMountResult.StorageFailure(CanvasMountFailure.of(stored.cause))
            CanvasMountMutation.OutcomeUnknown -> CanvasMountResult.OutcomeUnknown
            is CanvasMountMutation.Applied -> revealed(request, step, stored.canvas)
            is CanvasMountMutation.Removed -> CanvasMountResult.DefaultCleared
        }
    }

    private suspend fun revealed(
        request: CanvasMountRequest<CanvasMountScope>,
        step: MountStep,
        canvas: CanvasId,
    ): CanvasMountResult {
        val visible = !step.revealsStored || allows { maySee(request, canvas) }
        return if (visible) step.outcome(canvas) else CanvasMountResult.Denied
    }

    private suspend fun allows(question: suspend CanvasMountAuthority.() -> CanvasMountDecision): Boolean =
        runCatchingCancellable { authority.question() }.getOrNull() == CanvasMountDecision.ALLOW

    private fun CanvasMountRequest<CanvasMountScope>.isBounded(): Boolean {
        val conversation = (scope as? CanvasMountScope.Conversation)?.conversation?.value
        return isBoundedMountId(scope.agent.value) && conversation?.let(::isBoundedMountId) != false
    }

    /** Fixed limits shared by every caller. */
    companion object {
        /** Largest accepted id, in UTF-8 bytes, for agents, conversations, canvases and principals. */
        const val MAX_ID_BYTES: Int = 512
    }
}

/** One operation's shape for the shared authorize-then-run sequence. */
private class MountStep(
    val proposal: CanvasId?,
    val revealsStored: Boolean,
    val outcome: (CanvasId) -> CanvasMountResult,
    /** Whether the step removes the record: it then asks [CanvasMountAuthority.mayClear] before storage. */
    val clears: Boolean = false,
    /** Null means the addressed record is absent; a read's value is wrapped as an unwritten [CanvasMountMutation.Applied]. */
    val action: suspend (CanvasMountStorage) -> CanvasMountMutation?,
)

/** The raw, unauthorized mount operations; implemented only by [FileCanvasMountStore] and test doubles. */
internal interface CanvasMountStorage {
    suspend fun readBinding(agentId: AgentId, conversationId: ConversationId): CanvasId?
    suspend fun readDefault(agentId: AgentId): CanvasId?
    suspend fun bindIfAbsent(agentId: AgentId, conversationId: ConversationId, proposed: CanvasId): CanvasMountMutation
    suspend fun replaceBinding(agentId: AgentId, conversationId: ConversationId, canvasId: CanvasId): CanvasMountMutation
    suspend fun setDefault(agentId: AgentId, canvasId: CanvasId): CanvasMountMutation
    suspend fun clearDefault(agentId: AgentId): CanvasMountMutation
}

/** Where one raw mutation ended relative to its commit point, the atomic replacement of the record. */
internal sealed interface CanvasMountMutation {
    /** Whether the record may differ from before this call; once true, the outcome must not be lost. */
    val mayHaveChanged: Boolean

    /** The record durably holds [canvas]; [written] says whether this call replaced it. */
    data class Applied(
        /** The durable canvas: the caller's own, or an existing bind winner. */
        val canvas: CanvasId,
        /** False when an existing record was confirmed without being rewritten. */
        val written: Boolean,
    ) : CanvasMountMutation {
        override val mayHaveChanged: Boolean get() = written
    }

    /** The record is durably absent; [written] says whether this call removed it (false: there was none). */
    data class Removed(
        /** False when no record existed, so nothing was removed. */
        val written: Boolean,
    ) : CanvasMountMutation {
        override val mayHaveChanged: Boolean get() = written
    }

    /** Failed before the commit point; nothing changed. [cause] is for classification only. */
    class NotApplied(
        /** The failure, never exposed beyond a fixed [CanvasMountFailure]. */
        val cause: Throwable,
    ) : CanvasMountMutation {
        override val mayHaveChanged: Boolean get() = false
    }

    /** Failed at or after the commit point: the record may hold the old or the new canvas. Read back. */
    data object OutcomeUnknown : CanvasMountMutation {
        override val mayHaveChanged: Boolean get() = true
    }
}
