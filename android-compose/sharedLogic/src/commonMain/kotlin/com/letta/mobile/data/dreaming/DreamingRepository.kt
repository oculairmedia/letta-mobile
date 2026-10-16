package com.letta.mobile.data.dreaming

import com.letta.mobile.data.compaction.wireConversationIdOf
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/**
 * letta-mobile-iczm7: whose dreaming a request is about. letta-code keys the settings by agent but
 * reads them through a conversation's runtime (its working directory picks the project file), and
 * runs a dream over one conversation's transcript; null is the agent's `default` conversation.
 */
data class DreamTarget(val agentId: AgentId, val conversationId: ConversationId? = null) {
    val wireConversationId: String get() = wireConversationIdOf(agentId, conversationId)
}

/** A settings change and where letta-code should save it (null leaves letta-code's default, both files). */
data class DreamSettingsChange(val settings: DreamSettings, val scope: DreamSettingsScope? = null)

/** Why a backend cannot do dreaming, so the surface can say it once instead of failing each time. */
enum class DreamUnsupportedReason {
    /** No App Server to talk to: no connection, or one with no route to letta-code. */
    NoConnection,

    /** The Iroh host predates the dreaming relay: updating the host wrapper fixes it. */
    HostNeedsUpdate,

    /** The host has no native App Server client (a shim-era or degraded host). */
    NoNativeAppServer,

    /** The letta-code runtime has no `/dream` (letta-code before 0.33). */
    RuntimeTooOld,
}

/** What one dreaming request came to. */
sealed interface DreamOutcome<out T> {
    data class Done<T>(val value: T) : DreamOutcome<T>

    data class Unsupported(val reason: DreamUnsupportedReason) : DreamOutcome<Nothing>

    /** letta-code or the host refused or failed; [message] is their own text where they gave one. */
    data class Failed(val message: String) : DreamOutcome<Nothing>
}

/**
 * letta-mobile-iczm7: the dreaming operations, whichever way the session reaches letta-code — the
 * Iroh host's `reflection.*` ([AdminRpcDreamingRepository]) or a direct App Server client
 * ([AppServerDreamingRepository]). Both end in letta-code's own `get/set_reflection_settings` and
 * `/dream`.
 */
interface DreamingRepository {
    suspend fun settings(target: DreamTarget): DreamOutcome<DreamSettings>

    suspend fun save(target: DreamTarget, change: DreamSettingsChange): DreamOutcome<DreamSettings>

    /** Starts a dream now; [instruction] is an optional focus for it. */
    suspend fun runNow(target: DreamTarget, instruction: String? = null): DreamOutcome<DreamRunStart>
}

/** Tries this repository, then [fallback] for any request this one cannot serve at all. */
fun DreamingRepository.orElse(fallback: DreamingRepository): DreamingRepository = object : DreamingRepository {
    override suspend fun settings(target: DreamTarget) =
        firstSupported({ this@orElse.settings(target) }, { fallback.settings(target) })

    override suspend fun save(target: DreamTarget, change: DreamSettingsChange) =
        firstSupported({ this@orElse.save(target, change) }, { fallback.save(target, change) })

    override suspend fun runNow(target: DreamTarget, instruction: String?) =
        firstSupported({ this@orElse.runNow(target, instruction) }, { fallback.runNow(target, instruction) })
}

/**
 * The first answer that is not [DreamOutcome.Unsupported]; when both are unsupported, the more
 * specific reason wins (a host to update says more than "no connection").
 */
private suspend fun <T> firstSupported(
    primary: suspend () -> DreamOutcome<T>,
    fallback: suspend () -> DreamOutcome<T>,
): DreamOutcome<T> {
    val first = primary()
    if (first !is DreamOutcome.Unsupported) return first
    val second = fallback()
    return if (second is DreamOutcome.Unsupported && second.reason == DreamUnsupportedReason.NoConnection) first else second
}
