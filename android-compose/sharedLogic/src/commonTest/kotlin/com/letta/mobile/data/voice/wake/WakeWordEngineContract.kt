package com.letta.mobile.data.voice.wake

import com.letta.mobile.data.voice.WakePhrase
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.wake.assets.ReadyWakeWordAssets
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract every wake-word engine adapter must pass. Subclass it with the engine, two phrases it
 * accepts, one it must reject, and frames that contain each accepted phrase.
 */
abstract class WakeWordEngineContract {
    /** A fresh engine. */
    protected abstract fun engine(): WakeWordEngine

    /** Verified assets the engine opens on. */
    protected open fun assets(): ReadyWakeWordAssets = ReadyWakeWordAssets()

    /** A phrase the engine accepts. */
    protected abstract val first: WakePhrase

    /** Another phrase the engine accepts. */
    protected abstract val second: WakePhrase

    /** A phrase the engine must reject. */
    protected abstract val unusable: WakePhrase

    /** Frames in which [phrase] is spoken. */
    protected abstract fun utterance(phrase: WakePhrase): List<AudioFrame>

    /** A frame of the wrong size for the engine. */
    protected abstract fun wrongSizeFrame(): AudioFrame

    private suspend fun open(): WakeWordSession {
        val result = engine().open(assets())
        assertIs<WakeWordSessionResult.Opened>(result)
        return result.session
    }

    private fun WakeWordSession.hears(phrase: WakePhrase): Boolean =
        utterance(phrase).mapNotNull { process(it) }.any { it.phrase.text == phrase.text }

    @Test
    fun phrasesAreReplaceableWithoutReopening() = runTest {
        val session = open()
        session.setPhrases(listOf(first))
        assertTrue(session.hears(first))
        session.setPhrases(listOf(second))
        assertFalse(session.hears(first))
        assertTrue(session.hears(second))
        session.close()
    }

    @Test
    fun rejectedPhraseIsReportedAndDoesNotPoisonAcceptedOnes() = runTest {
        val session = open()
        val outcomes = session.setPhrases(listOf(first, unusable))
        assertEquals(listOf(first, unusable), outcomes.map { it.phrase })
        assertIs<PhraseOutcome.Accepted>(outcomes[0])
        assertIs<PhraseOutcome.Rejected>(outcomes[1])
        assertTrue(session.hears(first))
        session.close()
    }

    @Test
    fun nothingIsDetectedWithoutPhrases() = runTest {
        val session = open()
        assertFalse(session.hears(first))
        session.setPhrases(emptyList())
        assertFalse(session.hears(first))
        session.close()
    }

    @Test
    fun processAfterCloseIsANoOpAndCloseIsIdempotent() = runTest {
        val session = open()
        session.setPhrases(listOf(first))
        session.close()
        session.close()
        assertFalse(session.hears(first))
    }

    @Test
    fun wrongFrameSizeIsRefused() = runTest {
        val session = open()
        session.setPhrases(listOf(first))
        assertNull(session.process(wrongSizeFrame()))
        session.close()
    }
}

/** The contract run against the fake engine. */
class FakeWakeWordEngineContractTest : WakeWordEngineContract() {
    override val first = WakePhrase("hey letta")
    override val second = WakePhrase("okay computer")
    override val unusable = WakePhrase("xq zv kt")

    private val at = mapOf(first.text to 3L, second.text to 7L)

    override fun engine(): WakeWordEngine =
        FakeWakeWordEngine(script = FakeEngineScript(hits = at.entries.associate { (text, index) -> index to ScriptedHit(text) }))

    override fun utterance(phrase: WakePhrase): List<AudioFrame> = (0..(at.getValue(phrase.text).toInt())).map { frameAt(it) }

    override fun wrongSizeFrame(): AudioFrame = frameAt(3, FrameSize.OVERSIZED)
}
