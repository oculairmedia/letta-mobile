package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import io.ktor.client.network.sockets.ConnectTimeoutException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The closed-set classification of speech-server failures. A name that does not resolve must not be
 * reported as "couldn't reach the server": on 2026-11-18 exactly that ambiguity sent a whole setup
 * session chasing the phone's network while the host could not resolve the tailnet name at all
 * (bead letta-mobile-e86vv).
 */
class VoiceUpstreamWireClassifyTest {
    private fun codeOf(error: Exception): HostVoiceErrorCode =
        assertIs<VoiceUpstreamFailure>(VoiceUpstreamWire.classify(error)).code

    @Test
    fun aNameThatDoesNotResolveIsNotAPlainUnreachable() {
        assertEquals(
            HostVoiceErrorCode.UPSTREAM_UNRESOLVED,
            codeOf(UnknownHostException("primary-workstation.tail0a0e5.ts.net")),
        )
    }

    @Test
    fun anUnresolvedAddressSurfacedByTheSocketLayerIsUnresolved() {
        assertEquals(HostVoiceErrorCode.UPSTREAM_UNRESOLVED, codeOf(UnresolvedAddressException()))
    }

    @Test
    fun aWrappedNameFailureIsStillUnresolved() {
        // Engines wrap the resolver failure in their own type; the cause chain must be followed.
        assertEquals(
            HostVoiceErrorCode.UPSTREAM_UNRESOLVED,
            codeOf(IllegalStateException("request failed", UnknownHostException("speech.example.net"))),
        )
    }

    @Test
    fun aRefusedConnectionStaysPlainUnreachable() {
        val code = codeOf(ConnectException("Connection refused"))
        assertEquals(HostVoiceErrorCode.UPSTREAM_UNREACHABLE, code)
        assertTrue(code != HostVoiceErrorCode.UPSTREAM_UNRESOLVED)
    }

    @Test
    fun aTimeoutIsStillATimeoutAndOutranksResolution() {
        assertEquals(HostVoiceErrorCode.UPSTREAM_TIMEOUT, codeOf(SocketTimeoutException("read timed out")))
        assertEquals(
            HostVoiceErrorCode.UPSTREAM_TIMEOUT,
            codeOf(ConnectTimeoutException("connect timed out")),
        )
    }

    @Test
    fun anAlreadyClassifiedFailurePassesThroughUnchanged() {
        val already = VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_UNAUTHORIZED, 401)
        assertIs<VoiceUpstreamFailure>(VoiceUpstreamWire.classify(already))
        assertEquals(HostVoiceErrorCode.UPSTREAM_UNAUTHORIZED, codeOf(already))
    }

    @Test
    fun everyHostCodeMapsToAVoiceCode() {
        // Adding a host code without a voice code would surface as the generic failure text.
        HostVoiceErrorCode.entries.forEach { code ->
            assertTrue(code.wire.isNotBlank(), "${code.name} needs a wire string")
        }
        assertEquals(
            com.letta.mobile.data.voice.VoiceErrorCode.UNRESOLVED,
            HostVoiceErrorCode.UPSTREAM_UNRESOLVED.voiceCode,
        )
    }
}
