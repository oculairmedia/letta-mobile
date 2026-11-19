package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

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
        val outcome = try {
            // A budget overrun comes back as null rather than as an exception, so it can never reach
            // the classification below: the machine's resolver, not the classifier, would decide it.
            withTimeoutOrNull(DNS_BUDGET_MS) {
                try {
                    client.get(unresolvable)
                    Attempt.Answered
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    Attempt.Failed(expected)
                }
            }
        } finally {
            client.close()
        }

        val thrown = when (outcome) {
            null -> fail("the resolver did not answer within $DNS_BUDGET_MS ms, so this test skipped its subject")
            Attempt.Answered -> fail("the request did not fail, so this test proves nothing about resolution")
            is Attempt.Failed -> outcome.error
        }
        val failure = VoiceUpstreamWire.classify(thrown)
        assertIs<VoiceUpstreamFailure>(failure)
        assertEquals(
            HostVoiceErrorCode.UPSTREAM_UNRESOLVED,
            failure.code,
            "a real resolution failure must not be reported as a plain unreachable: ${thrown::class.simpleName}",
        )
    }

    /** What the request did within the budget. */
    private sealed interface Attempt {
        data object Answered : Attempt

        class Failed(val error: Exception) : Attempt
    }

    private companion object {
        /** Long enough for a slow resolver; a test that outlives it fails loudly instead of misreporting. */
        const val DNS_BUDGET_MS = 20_000L
    }
}
