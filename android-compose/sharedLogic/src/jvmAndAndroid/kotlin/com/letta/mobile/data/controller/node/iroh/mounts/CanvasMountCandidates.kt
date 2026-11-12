package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.mount.CanvasMountDecision
import com.letta.mobile.data.canvas.mount.CanvasMountRequest
import com.letta.mobile.data.canvas.mount.CanvasMountScope
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One canvas `canvas.mount.candidates` lists: its id, the directory title when known, and its owner KIND only. */
internal data class CanvasMountCandidate(val canvas: CanvasId, val title: String?, val ownerKind: String)

/**
 * `canvas.mount.candidates` (owner decision D6): the canvases the caller may mount for one binding.
 *
 * Same check order as every route: the router's agent scope guard, verified identity and strict params (the handler),
 * then [HostCanvasMountAuthority.mayAddress] for the binding, whose refusal is the one byte-identical denial. Each
 * listed canvas then passes [HostCanvasMountAuthority.mayList], which is the proposal rule itself, so a canvas a bind
 * or replace would refuse is never listed. The binding's own canvas comes first (always mountable, "go back").
 *
 * Only the host's agent canvas directory is enumerated: canvases only the relay holds (app-made ones) are not, since
 * the relay store has no listing and the app that made one already has it locally. At most
 * [CanvasMountWire.MAX_CANDIDATES] canvases are answered; a failed host lookup answers `unavailable`, never a
 * partial list.
 */
internal class CanvasMountCandidates(private val authority: HostCanvasMountAuthority, private val catalog: CanvasMountCatalog) {
    suspend fun list(request: CanvasMountRequest<CanvasMountScope.Conversation>): CanvasMountOutcome {
        if (authority.mayAddress(request) != CanvasMountDecision.ALLOW) return CanvasMountOutcome.Denied
        val entries = runCatchingCancellable { catalog.known() }.getOrElse { failure ->
            if (failure is Error) throw failure
            return CanvasMountOutcome.Unavailable(CanvasMountUnavailable.AUTHORITY)
        }
        val own = CanvasId.forConversation(request.scope.conversation)
        val ownCandidate = CanvasMountCandidate(own, entries.firstOrNull { it.id == own }?.title, CanvasMountWire.OWNER_CONVERSATION)
        val all = listOf(ownCandidate) + listable(request, entries.filter { it.id != own })
        return CanvasMountOutcome.Listed(all.take(CanvasMountWire.MAX_CANDIDATES), truncated = all.size > CanvasMountWire.MAX_CANDIDATES)
    }

    /** Up to [CanvasMountWire.MAX_CANDIDATES] of [entries] the proposal rule admits: one more than fits beside the own canvas. */
    private suspend fun listable(
        request: CanvasMountRequest<CanvasMountScope.Conversation>,
        entries: List<HostCanvasEntry>,
    ): List<CanvasMountCandidate> {
        val found = mutableListOf<CanvasMountCandidate>()
        val remaining = entries.iterator()
        while (found.size < CanvasMountWire.MAX_CANDIDATES && remaining.hasNext()) {
            val entry = remaining.next()
            if (authority.mayList(request, entry.id) == CanvasMountDecision.ALLOW) found += entry.candidate()
        }
        return found
    }

    private fun HostCanvasEntry.candidate(): CanvasMountCandidate {
        val kind = if (conversationId != null) CanvasMountWire.OWNER_CONVERSATION else CanvasMountWire.OWNER_AGENT
        return CanvasMountCandidate(id, title, kind)
    }
}

/** The `listed` result body: every candidate in order, then whether more were left out. */
internal fun listedBody(candidates: List<CanvasMountCandidate>, truncated: Boolean): JsonElement = buildJsonObject {
    put(CanvasMountWire.STATUS, CanvasMountWire.LISTED)
    putJsonArray(CanvasMountWire.CANVASES) {
        candidates.forEach { candidate ->
            addJsonObject {
                put(CanvasMountWire.CANVAS_ID, candidate.canvas.value)
                candidate.title?.let { put(CanvasMountWire.TITLE, it) }
                put(CanvasMountWire.OWNER_KIND, candidate.ownerKind)
            }
        }
    }
    put(CanvasMountWire.TRUNCATED, truncated)
}
