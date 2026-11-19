package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Proves the assumption the classifier rests on: a real engine (CIO here, OkHttp on Android)
 * hands back a failure the classifier recognises as a name-resolution one. A unit test over fake
 * exceptions cannot show this, so a genuinely unregistrable host name is dialled through the engine
 * and the resulting error is classified.
 *
 * The shape this guards against is the one that cost a setup session on 2026-11-18 (bead
 * letta-mobile-e86vv): the host could not resolve a tailnet name and the app said only "couldn't
 * reach the speech server".
 */
class VoiceUpstreamUnresolvedNameRealEngineTest {
    @Test
    fun aNameThatCannotResolveIsClassifiedAsUnresolvedByARealEngine() = runBlocking {
        // .invalid is reserved by RFC 2606 and never resolves, so this fails at resolution, not routing.
        val unresolvable = Url("http://voice-diag-e86vv.invalid/v1/models")
        val client = HttpClient(CIO)
        val thrown = try {
            withTimeout(20_000) { client.get(unresolvable) }
            null
        } catch (expected: Exception) {
            expected
        } finally {
            client.close()
        }

        assertNotNull(thrown, "the request did not fail, so this test proves nothing about resolution")
        val failure = VoiceUpstreamWire.classify(thrown)
        assertIs<VoiceUpstreamFailure>(failure)
        assertEquals(
            HostVoiceErrorCode.UPSTREAM_UNRESOLVED,
            failure.code,
            "a real resolution failure must not be reported as a plain unreachable: ${thrown::class.simpleName}",
        )
    }
}
