package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.host.HostVoiceConnectionCandidate
import com.letta.mobile.data.voice.host.HostVoiceEndpoint
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceException
import com.letta.mobile.data.voice.host.HostVoiceModels
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import com.letta.mobile.data.voice.host.HostVoiceTestResult
import com.letta.mobile.data.voice.host.HostVoiceWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The host's shared speech setup as this device last learned it.
 *
 * [supported] is whether the connected host serves the voice-endpoint contract; without it there is
 * no shared setup and the device keeps its own. [status] is the last status read (kept across a
 * failed read, so a host setup in force never silently gives way to this device's own); [admin] is
 * whether this device may change it (null until known); [error] is the last request's failure.
 */
data class HostVoiceState(
    /** Whether the host advertises the voice-endpoint contract. */
    val supported: Boolean = false,
    /** The last status read; null until one was. */
    val status: HostVoiceSetupStatus? = null,
    /** Whether this device holds admin rights on the host; null until learned. */
    val admin: Boolean? = null,
    /** The last request's failure; null after a success. */
    val error: HostVoiceErrorCode? = null,
) {
    /** Whether the host has a speech server set up, as last read. */
    val configured: Boolean get() = supported && status?.configured == true

    /**
     * The shared server's origin (scheme, host, port), which this device's audio acknowledgement
     * names; null when nothing is set up.
     */
    val origin: EndpointOrigin?
        get() {
            val current = status?.takeIf { it.configured } ?: return null
            val fromUrl = current.baseUrl?.let { EndpointOrigin.of(SpeechEndpointUrl(it)) }
            return fromUrl ?: current.consent.origin?.let(::EndpointOrigin)
        }
}

/**
 * The shared speech setup on the connected host, through [endpoint], with what this device knows of
 * it in [state]. Reads go to every device; writes, clears and checks need admin rights, which
 * [learnAdmin] finds out with a check that sends no audio or text. Nothing here logs, and the key a
 * write may carry is never kept.
 */
class SharedVoiceSetup(
    private val link: HostVoiceLink,
    /** The host's voice endpoint over [link]. */
    val endpoint: HostVoiceEndpoint = IrohHostVoiceEndpoint(link),
) {
    private val mutableState = MutableStateFlow(HostVoiceState())

    /** What this device last learned. */
    val state: StateFlow<HostVoiceState> = mutableState.asStateFlow()

    /** Whether the connected host serves the voice-endpoint contract right now. */
    val supported: Boolean get() = HostVoiceWire.CAPABILITY in link.hostCapabilities

    /** Reads the shared status again; a host without the contract leaves [state] unsupported. */
    suspend fun refresh(): HostVoiceState {
        if (!supported) return HostVoiceState().also { mutableState.value = it }
        mutableState.update { it.copy(supported = true) }
        runRecording { endpoint.status() }?.let { status -> mutableState.update { it.copy(status = status) } }
        return mutableState.value
    }

    /**
     * Learns whether this device may change the shared setup: a connection check of the stored setup
     * (no audio or text; it also refreshes the host's reachability) is refused as forbidden only
     * for a device without admin rights. Re-reads the status afterwards.
     */
    suspend fun learnAdmin(): HostVoiceState {
        if (!supported) return refresh()
        val outcome = runCatching { endpoint.testConnection() }
        val code = (outcome.exceptionOrNull() as? HostVoiceException)?.code
        outcome.exceptionOrNull()?.let { if (it !is HostVoiceException) throw it }
        // A check that never reached the voice routes (the link failed, an old host) proves nothing.
        val admin = when (code) {
            HostVoiceErrorCode.NOT_AUTHORIZED -> false
            HostVoiceErrorCode.INTERNAL, HostVoiceErrorCode.UNSUPPORTED -> mutableState.value.admin
            else -> true
        }
        mutableState.update { it.copy(admin = admin) }
        return refresh()
    }

    /** Stores [command] on the host; the new status, or null with [HostVoiceState.error] set. */
    suspend fun write(command: HostVoiceSetupCommand): HostVoiceSetupStatus? =
        runRecording { endpoint.writeSetup(command) }?.also { status -> mutableState.update { it.copy(status = status) } }

    /** Removes the shared setup at the revision last read; the new status, or null on failure. */
    suspend fun clear(): HostVoiceSetupStatus? {
        val revision = mutableState.value.status?.revision
        return runRecording { endpoint.clearSetup(revision) }?.also { status -> mutableState.update { it.copy(status = status) } }
    }

    /** Checks [candidate], or the stored setup when null; the result, or null on failure. */
    suspend fun test(candidate: HostVoiceConnectionCandidate? = null): HostVoiceTestResult? =
        runRecording { endpoint.testConnection(candidate) }

    /** The stored server's models and voices; null on failure. */
    suspend fun models(): HostVoiceModels? = runRecording { endpoint.models() }

    /**
     * Where a session goes by what is known now, without waiting: through the host while it serves
     * the contract and its setup (as last read) is configured, unless [preferDevice] (the person
     * chose this device's own server); otherwise directly. A failed read keeps the last status, so a
     * host setup in force never silently gives way to this device's own.
     */
    fun route(preferDevice: Boolean): VoiceRoute {
        val known = mutableState.value
        val status = known.status?.takeIf { it.configured }
        if (preferDevice || !supported || status == null) return VoiceRoute.Direct
        return VoiceRoute.Host(endpoint, status, known.origin)
    }

    /** [route] after reading the shared status again (skipped when [preferDevice]). */
    suspend fun resolveRoute(preferDevice: Boolean): VoiceRoute {
        if (!preferDevice) refresh()
        return route(preferDevice)
    }

    /** Whether a press cannot yet tell the route: the host serves the contract but no status was read. */
    val pending: Boolean get() = supported && mutableState.value.status == null

    /** Runs [request], recording its failure code in [state] (cancellation passes through). */
    private suspend fun <T> runRecording(request: suspend () -> T): T? {
        val outcome = runCatching { request() }
        val failure = outcome.exceptionOrNull()
        if (failure != null && failure !is HostVoiceException) throw failure
        mutableState.update { it.copy(error = (failure as? HostVoiceException)?.code) }
        return outcome.getOrNull()
    }
}
