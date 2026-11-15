package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.host.HostSpeechSetup
import com.letta.mobile.data.voice.host.HostTranscriptionSetup
import com.letta.mobile.data.voice.host.HostVoiceConsent
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceKeyChange
import com.letta.mobile.data.voice.host.HostVoiceReachability
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.session.VoiceEndpointRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A refusal with one code of the contract's closed set; the route answers exactly [code]'s wire string. */
internal class VoiceRefusal(val code: HostVoiceErrorCode) : Exception(code.wire)

/** The stored setup at one revision, with its key, for one upstream call. Prints no key. */
internal class VoiceSetupSnapshot(
    val revision: Long,
    val setup: StoredVoiceSetup,
    val apiKey: String?,
) {
    /** Where upstream calls for this setup go. */
    val target: VoiceUpstreamTarget get() = VoiceUpstreamTarget(setup.baseUrl, apiKey)

    override fun toString(): String = "VoiceSetupSnapshot(revision=$revision, keySet=${apiKey != null})"
}

/**
 * The host's one shared speech setup: a setup file without the key ([persistence]) and the key in the
 * host's key store ([keys]). Every write takes a new revision and resets reachability; a write to a new
 * origin drops the key and the grants it does not restate. All access is serialised.
 */
internal class VoiceSetupStore(
    private val persistence: VoiceSetupPersistence,
    private val keys: VoiceKeyStore,
    private val clock: () -> Long,
) {
    private val lock = Mutex()

    /** The status any device may read. */
    suspend fun status(): HostVoiceSetupStatus = lock.withLock { statusOf(persistence.read()) }

    /** The stored setup with its key, or null when nothing is stored. */
    suspend fun snapshot(): VoiceSetupSnapshot? = lock.withLock {
        val state = persistence.read()
        state.setup?.let { VoiceSetupSnapshot(state.revision, it, keys.read(it.origin)) }
    }

    /** The key stored for [origin], or null; used only to check a candidate on the stored origin. */
    suspend fun keyFor(origin: String): String? = lock.withLock { keys.read(origin) }

    /** Applies [command] and returns the new status. Throws [VoiceRefusal]. Contacts no server. */
    suspend fun write(command: HostVoiceSetupCommand): HostVoiceSetupStatus = lock.withLock {
        val state = persistence.read()
        requireRevision(state, command.expectedRevision)
        val baseUrl = command.endpoint.baseUrl.value
        val origin = originOf(baseUrl) ?: throw VoiceRefusal(HostVoiceErrorCode.INVALID_REQUEST)
        val previous = state.setup?.takeIf { it.origin == origin }
        requireKeyTransport(baseUrl, keyAfterWrite(command.apiKey, origin, keepable = previous != null))
        val setup = StoredVoiceSetup(
            baseUrl = baseUrl,
            transcriptionModel = command.endpoint.transcriptionModel?.value,
            speechModel = command.endpoint.speechModel?.value,
            voice = command.endpoint.voice?.value,
            language = command.endpoint.language?.value,
            grants = command.grants,
            grantedAtMs = grantedAt(previous, command.grants),
        )
        val next = VoiceSetupState(state.revision + 1, setup)
        withKeyRollback { applyKey(command.apiKey, origin, keepable = previous != null) }.persist(next)
        statusOf(next)
    }

    /** Deletes the setup, its key and its grants and returns the empty status. Throws [VoiceRefusal]. */
    suspend fun clear(expectedRevision: String?): HostVoiceSetupStatus = lock.withLock {
        val state = persistence.read()
        requireRevision(state, expectedRevision)
        val next = VoiceSetupState(state.revision + if (state.setup != null) 1 else 0, null)
        withKeyRollback { keys.delete() }.persist(next)
        statusOf(next)
    }

    /** Records what a check of the setup at [revision] found; ignored when the setup changed since. */
    suspend fun recordReachability(revision: Long, reachability: HostVoiceReachability) = lock.withLock {
        val state = persistence.read()
        val setup = state.setup ?: return@withLock
        if (state.revision != revision) return@withLock
        persistence.write(state.copy(setup = setup.copy(reachability = reachability)))
    }

    /**
     * Applies [change] to the key store and returns how to persist the matching setup: if that write
     * fails, the key store is put back as it was (the earlier key, or none) and the failure is rethrown,
     * so the key never changes without its setup.
     */
    private fun withKeyRollback(change: () -> Unit): KeyRollback {
        val before = persistence.read().setup?.origin?.let { origin -> keys.read(origin)?.let { origin to it } }
        change()
        return KeyRollback(before)
    }

    private inner class KeyRollback(private val before: Pair<String, String>?) {
        fun persist(state: VoiceSetupState) {
            var written = false
            try {
                persistence.write(state)
                written = true
            } finally {
                if (!written) restore()
            }
        }

        private fun restore() {
            val earlier = before
            if (earlier == null) keys.delete() else keys.write(earlier.first, earlier.second)
        }

        override fun toString(): String = "KeyRollback(keySet=${before != null})"
    }

    private fun requireRevision(state: VoiceSetupState, expected: String?) {
        if (expected != null && expected != state.revisionName()) throw VoiceRefusal(HostVoiceErrorCode.REVISION_CONFLICT)
    }

    private fun keyAfterWrite(change: HostVoiceKeyChange, origin: String, keepable: Boolean): Boolean = when (change) {
        is HostVoiceKeyChange.Replace -> true
        HostVoiceKeyChange.Remove -> false
        HostVoiceKeyChange.Keep -> keepable && keys.read(origin) != null
    }

    /** A key may only be kept for https, or plain http to a loopback literal as the host sees it. */
    private fun requireKeyTransport(baseUrl: String, withKey: Boolean) {
        if (withKey && VoiceEndpointRules.problem(baseUrl, withKey = true) == VoiceErrorCode.INSECURE_TRANSPORT) {
            throw VoiceRefusal(HostVoiceErrorCode.INSECURE_TRANSPORT)
        }
    }

    /**
     * Stores, deletes or keeps the key. The key is written before the setup file and bound to [origin],
     * so a crash between the two never lets a key reach a server it was not entered for.
     */
    private fun applyKey(change: HostVoiceKeyChange, origin: String, keepable: Boolean) {
        when (change) {
            is HostVoiceKeyChange.Replace -> keys.write(origin, change.key.revealForEncoding())
            HostVoiceKeyChange.Remove -> keys.delete()
            HostVoiceKeyChange.Keep -> if (!keepable) keys.delete()
        }
    }

    /** The grant time stays while the origin and the grants are unchanged; no grant has no time. */
    private fun grantedAt(previous: StoredVoiceSetup?, grants: HostVoiceGrants): Long? = when {
        !grants.audioForTranscription && !grants.textForSynthesis -> null
        previous != null && previous.grants == grants && previous.grantedAtMs != null -> previous.grantedAtMs
        else -> clock()
    }

    private fun statusOf(state: VoiceSetupState): HostVoiceSetupStatus {
        val setup = state.setup ?: return HostVoiceSetupStatus(HostVoiceWire.CONTRACT_VERSION, configured = false)
        return HostVoiceSetupStatus(
            contractVersion = HostVoiceWire.CONTRACT_VERSION,
            configured = true,
            revision = state.revisionName(),
            baseUrl = setup.baseUrl,
            hasApiKey = keys.read(setup.origin) != null,
            transcription = HostTranscriptionSetup(setup.transcriptionAvailable, setup.transcriptionModel, setup.language),
            speech = HostSpeechSetup(setup.speechAvailable, setup.speechModel, setup.voice),
            consent = HostVoiceConsent(setup.origin, setup.grants, setup.grantedAtMs),
            reachability = setup.reachability,
        )
    }

    private fun VoiceSetupState.revisionName(): String? = setup?.let { "$REVISION_PREFIX$revision" }

    private companion object {
        const val REVISION_PREFIX = "rev-"
    }
}

/** Listening through the host is usable: a transcription model and the audio grant. */
internal val StoredVoiceSetup.transcriptionAvailable: Boolean
    get() = transcriptionModel != null && grants.audioForTranscription

/** Speaking through the host is usable: a speech model, a voice and the text grant. */
internal val StoredVoiceSetup.speechAvailable: Boolean
    get() = speechModel != null && voice != null && grants.textForSynthesis
