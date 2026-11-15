package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.host.HostVoiceCatalogEntry
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceListSource
import com.letta.mobile.data.voice.host.HostVoiceModels
import com.letta.mobile.data.voice.host.HostVoiceReachability
import com.letta.mobile.data.voice.host.HostVoiceReachabilityState
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import com.letta.mobile.data.voice.host.HostVoiceTestResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `voice.setup.get`, `.set`, `.clear`, `.test` and `voice.models.list`: the shared setup and the checks
 * of its server. Writes never contact the server; checks send no audio or text, so they need no grant.
 */
internal class VoiceSetupHandlers(
    private val store: VoiceSetupStore,
    private val upstream: VoiceSpeechUpstream,
    private val clock: () -> Long,
) {
    /** The shared status, never the key. */
    suspend fun get(): JsonElement = store.status().toWire()

    /** Writes the setup and returns the new status. */
    suspend fun set(params: JsonObject?): JsonElement = store.write(VoiceParams.setupCommand(params)).toWire()

    /** Clears the setup and returns the empty status. */
    suspend fun clear(params: JsonObject?): JsonElement = store.clear(VoiceParams.clearRevision(params)).toWire()

    /**
     * Checks the stored setup (`{}`, recording reachability) or a candidate it then forgets. A candidate
     * without a key gets the stored key only when it has the stored key's origin.
     */
    suspend fun test(params: JsonObject?): JsonElement {
        val candidate = VoiceParams.candidate(params)
        val models = if (candidate == null) {
            val snapshot = store.snapshot() ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
            checked(snapshot) { upstream.models(snapshot.target) }
        } else {
            val key = candidate.apiKey ?: originOf(candidate.baseUrl)?.let { store.keyFor(it) }
            upstreamCall { upstream.models(VoiceUpstreamTarget(candidate.baseUrl, key)) }
        }
        return voiceWire(HostVoiceTestResult.serializer(), HostVoiceTestResult(models.size, clock()))
    }

    /**
     * The stored server's models, and its voices when it lists them (else only the stored voice).
     * Refreshes reachability from the models call.
     */
    suspend fun models(): JsonElement {
        val snapshot = store.snapshot() ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        val models = checked(snapshot) { upstream.models(snapshot.target) }
        val listed = try {
            upstream.voices(snapshot.target)
        } catch (expectedFailure: VoiceUpstreamFailure) {
            null
        }
        val catalog = HostVoiceModels(
            models = models.map(::HostVoiceCatalogEntry),
            voices = (listed ?: listOfNotNull(snapshot.setup.voice)).map(::HostVoiceCatalogEntry),
            voicesSource = if (listed != null) HostVoiceListSource.UPSTREAM else HostVoiceListSource.CONFIGURED,
            checkedAtMs = clock(),
        )
        return voiceWire(HostVoiceModels.serializer(), catalog)
    }

    /** Runs a check of the stored setup and records what it found against that revision. */
    private suspend fun <T> checked(snapshot: VoiceSetupSnapshot, call: suspend () -> T): T {
        val outcome = runCatching { upstreamCall { call() } }
        val refusal = outcome.exceptionOrNull()
        if (refusal != null && refusal !is VoiceRefusal) throw refusal
        val reachability = when (refusal) {
            null -> HostVoiceReachability(HostVoiceReachabilityState.REACHABLE, clock())
            else -> HostVoiceReachability(HostVoiceReachabilityState.FAILED, clock(), (refusal as VoiceRefusal).code)
        }
        store.recordReachability(snapshot.revision, reachability)
        return outcome.getOrThrow()
    }

    private fun HostVoiceSetupStatus.toWire(): JsonElement = voiceWire(HostVoiceSetupStatus.serializer(), this)
}

/** Runs an upstream call, turning its failure into the route's refusal with the same code. */
internal suspend fun <T> upstreamCall(call: suspend () -> T): T = try {
    call()
} catch (expectedFailure: VoiceUpstreamFailure) {
    throw VoiceRefusal(expectedFailure.code)
}
