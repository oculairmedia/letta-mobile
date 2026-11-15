package com.letta.mobile.data.controller.node.iroh.voice

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** The two kinds of voice request whose concurrency the contract limits. */
internal enum class VoiceRequestKind {
    TRANSCRIPTION,
    SPEECH,
}

/** A held in-flight slot; [release] is idempotent. */
internal fun interface VoiceSlot {
    fun release()
}

/**
 * The contract's concurrency limits: at most one request of each kind in flight per connection, and at
 * most [maxUpstreamSpeech] speech calls to the server across the whole host. [tryAcquire] never waits:
 * a request over a limit is refused with `voice.busy`.
 */
internal class VoiceInFlight(maxUpstreamSpeech: Int = DEFAULT_MAX_UPSTREAM_SPEECH) {
    private val held = ConcurrentHashMap.newKeySet<Pair<Any, VoiceRequestKind>>()
    private val upstreamSpeech = Semaphore(maxUpstreamSpeech)

    /** A slot for a [kind] request on [connection], or null when one is in flight or the host is at its limit. */
    fun tryAcquire(connection: Any, kind: VoiceRequestKind): VoiceSlot? {
        val key = connection to kind
        if (!held.add(key)) return null
        if (kind == VoiceRequestKind.SPEECH && !upstreamSpeech.tryAcquire()) {
            held.remove(key)
            return null
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return VoiceSlot {
            if (released.compareAndSet(false, true)) {
                held.remove(key)
                if (kind == VoiceRequestKind.SPEECH) upstreamSpeech.release()
            }
        }
    }

    /** Requests in flight right now, for tests. */
    val heldCount: Int get() = held.size

    companion object {
        /** Host-wide limit on concurrent speech calls to the server. */
        const val DEFAULT_MAX_UPSTREAM_SPEECH = 4
    }
}
