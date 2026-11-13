package com.letta.mobile.data.voice.vad.ear

import app.cash.turbine.test
import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.endpoint.SpeechOperation
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun VadVoiceEar.turns(): Flow<VoiceEarEvent> = listen().filterNot { it is VoiceEarEvent.InputLevel }

private fun said(words: String) = VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText(words)))

private fun failed(code: VoiceErrorCode, retry: CapturedAudioHandle? = null) = VoiceEarEvent.Failed(VoiceError(code, VoicePort.EAR), retry)

private val unreachable = SpeechEndpointException(SpeechOperation.TRANSCRIPTION, VoiceErrorCode.UNREACHABLE)

private val noGrant = SpeechEndpointException(SpeechOperation.TRANSCRIPTION, VoiceErrorCode.PERMISSION_REQUIRED)

@OptIn(ExperimentalCoroutinesApi::class)
class VadVoiceEarTest {
    @Test
    fun speechThenSilenceIsOneTranscribedTurn() = runTest {
        val source = ScriptedSource(turn())
        val transcriber = ScriptedTranscriber(heard("hello"))
        valueEar(source, transcriber).turns().test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            assertEquals(VoiceEarEvent.SpeechStarted, awaitItem())
            assertEquals(said("hello"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(source.open)
    }

    @Test
    fun theTranscribedClipStartsWithThePreRoll() = runTest {
        val transcriber = ScriptedTranscriber(heard("hello"))
        valueEar(ScriptedSource(turn()), transcriber).turns().test {
            skipItems(2)
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        val samples = transcriber.clips.single().samples.map { it.toInt() }
        assertEquals(16_000, transcriber.clips.single().format.sampleRateHz)
        assertEquals(4 * 512, samples.takeWhile { it == PRE_ROLL_VALUE }.size)
        assertEquals(12 * 512, samples.count { it == SPEECH_VALUE })
    }

    @Test
    fun everyFrameReportsItsLevel() = runTest {
        valueEar(ScriptedSource(frames(3_277, 3)), ScriptedTranscriber()).listen().test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            repeat(3) { assertEquals(0.6f, assertIs<VoiceEarEvent.InputLevel>(awaitItem()).level.value, 0.01f) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedTranscriptionIsRetriedOnceThroughTheRunningListen() = runTest {
        val transcriber = ScriptedTranscriber(failing(unreachable), heard("again"))
        val ear = valueEar(ScriptedSource(turn()), transcriber)
        ear.turns().test {
            skipItems(2)
            val failure = assertIs<VoiceEarEvent.Failed>(awaitItem())
            assertEquals(VoiceError(VoiceErrorCode.UNREACHABLE, VoicePort.EAR), failure.error)
            val handle = assertNotNull(failure.retry)
            assertTrue(ear.retry(handle))
            assertEquals(said("again"), awaitItem())
            assertFalse(ear.retry(handle))
            cancelAndIgnoreRemainingEvents()
        }
        assertContentEquals(transcriber.clips[0].samples, transcriber.clips[1].samples)
    }

    @Test
    fun theRetainedCaptureSurvivesTheListenBeingClosed() = runTest {
        val source = ScriptedSource(turn())
        val ear = valueEar(source, ScriptedTranscriber(failing(unreachable), heard("again")))
        val handle = ear.turns().firstFailure()
        source.script = frames(0, 2)
        ear.turns().test {
            // The loop retries only once the reopened ear reports it is listening.
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            assertTrue(ear.retry(handle))
            assertEquals(said("again"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedRetryOffersNoSecondRetry() = runTest {
        val ear = valueEar(ScriptedSource(turn()), ScriptedTranscriber(failing(unreachable), failing(unreachable)))
        ear.turns().test {
            skipItems(2)
            val handle = assertNotNull(assertIs<VoiceEarEvent.Failed>(awaitItem()).retry)
            assertTrue(ear.retry(handle))
            assertEquals(failed(VoiceErrorCode.UNREACHABLE), awaitItem())
            assertFalse(ear.retry(handle))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theNextSpeechWipesTheRetainedCapture() = runTest {
        val source = ScriptedSource(turn())
        val ear = valueEar(source, ScriptedTranscriber(failing(unreachable)))
        val handle = ear.turns().firstFailure()
        source.script = openTurn()
        ear.turns().test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            assertEquals(VoiceEarEvent.SpeechStarted, awaitItem())
            assertFalse(ear.retry(handle))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun closeRefusesRetriesAndListens() = runTest {
        val ear = valueEar(ScriptedSource(turn()), ScriptedTranscriber(failing(unreachable)))
        val handle = ear.turns().firstFailure()
        ear.close()
        assertFalse(ear.retry(handle))
        ear.turns().test {
            assertEquals(failed(VoiceErrorCode.UNAVAILABLE), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun commitEndsTheTurnAtOnce() = runTest {
        val ear = valueEar(ScriptedSource(openTurn()), ScriptedTranscriber(heard("now")))
        ear.turns().test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            assertEquals(VoiceEarEvent.SpeechStarted, awaitItem())
            ear.commitTurn()
            assertEquals(said("now"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun commitWithNothingHeardSendsNothing() = runTest {
        val transcriber = ScriptedTranscriber()
        val ear = valueEar(ScriptedSource(frames(0, 10)), transcriber)
        ear.turns().test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            ear.commitTurn()
            delay(1_000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(transcriber.clips.isEmpty())
    }

    @Test
    fun aCommitBeforeListeningIsNotReplayed() = runTest {
        val ear = valueEar(ScriptedSource(openTurn()), ScriptedTranscriber(heard("late")))
        ear.commitTurn()
        ear.turns().test {
            skipItems(2)
            delay(1_000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theEchoGuardIgnoresModerateSpeechWhileTheAppSpeaks() = runTest {
        // Barge-in needs 400 ms of voice (25 hops): 14 frames are 28 hops.
        val source = ScriptedSource(frames(PRE_ROLL_VALUE, 4) + frames(600, 12) + frames(0, 40) + frames(SPEECH_VALUE, 14))
        val transcriber = ScriptedTranscriber(heard("stop"))
        val ear = valueEar(source, transcriber)
        ear.setOutputActive(true)
        ear.turns().test {
            assertEquals(VoiceEarEvent.ListeningStarted, awaitItem())
            assertEquals(VoiceEarEvent.SpeechStarted, awaitItem())
            ear.commitTurn()
            assertEquals(said("stop"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(transcriber.clips.single().samples.none { it.toInt() == 600 })
    }

    @Test
    fun sourceFailuresAreTypedAndTheSourceIsReleased() = runTest {
        listOf(VoiceErrorCode.PERMISSION_REQUIRED, VoiceErrorCode.UNAVAILABLE).forEach { code ->
            val source = ScriptedSource(emptyList(), SourceEnding.Fail(code))
            valueEar(source, ScriptedTranscriber()).turns().test {
                assertEquals(failed(code), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertFalse(source.open)
        }
    }

    @Test
    fun aSourceThatEndsOnItsOwnIsAFailure() = runTest {
        valueEar(ScriptedSource(emptyList(), SourceEnding.Complete), ScriptedTranscriber()).turns().test {
            assertEquals(failed(VoiceErrorCode.ENGINE_FAILED), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aSourceInAnotherFormatIsRefusedUnopened() = runTest {
        val source = ScriptedSource(turn(), format = AudioFrameFormat(sampleRateHz = 48_000))
        valueEar(source, ScriptedTranscriber()).turns().test {
            assertEquals(failed(VoiceErrorCode.UNSUPPORTED_FORMAT), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, source.opens)
    }

    @Test
    fun transcriberFailuresKeepTheirCodes() = runTest {
        val table = listOf<Pair<Throwable, VoiceErrorCode>>(
            unreachable to VoiceErrorCode.UNREACHABLE,
            noGrant to VoiceErrorCode.PERMISSION_REQUIRED,
            VoiceAudioException(VoiceErrorCode.UNSUPPORTED_FORMAT) to VoiceErrorCode.UNSUPPORTED_FORMAT,
            IllegalStateException("boom") to VoiceErrorCode.TRANSCRIPTION_FAILED,
        )
        table.forEach { (error, code) ->
            val failure = valueEar(ScriptedSource(turn()), ScriptedTranscriber(failing(error))).turns().firstFailed()
            assertEquals(VoiceError(code, VoicePort.EAR), failure.error)
            assertNotNull(failure.retry)
        }
    }

    @Test
    fun nothingIsEmittedAfterCancelAndTheSourceIsAlreadyReleased() = runTest {
        val source = ScriptedSource(openTurn())
        val events = mutableListOf<VoiceEarEvent>()
        val ear = valueEar(source, ScriptedTranscriber(heard("late")))
        val job = launch { ear.listen().collect { events += it } }
        advanceUntilIdle()
        assertTrue(source.open)
        ear.commitTurn()
        job.cancelAndJoin()
        assertFalse(source.open)
        val seen = events.size
        advanceUntilIdle()
        assertEquals(seen, events.size)
    }

    private suspend fun Flow<VoiceEarEvent>.firstFailed(): VoiceEarEvent.Failed {
        var failure: VoiceEarEvent.Failed? = null
        test {
            skipItems(2)
            failure = assertIs<VoiceEarEvent.Failed>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        return assertNotNull(failure)
    }

    private suspend fun Flow<VoiceEarEvent>.firstFailure(): CapturedAudioHandle = assertNotNull(firstFailed().retry)
}

class EarMemoryTest {
    private fun captured(handle: Long) = CapturedTurn(CapturedAudioHandle(handle), ShortArray(4) { 5 })

    @Test
    fun keepingANewCaptureWipesTheOldOne() {
        val memory = EarMemory()
        val first = captured(1)
        memory.retain(first)
        memory.retain(captured(2))
        assertTrue(first.samples.all { it.toInt() == 0 })
        assertNull(memory.take(CapturedAudioHandle(1)))
    }

    @Test
    fun aCaptureIsTakenOnce() {
        val memory = EarMemory()
        val kept = captured(3)
        memory.retain(kept)
        assertEquals(kept, memory.take(CapturedAudioHandle(3)))
        assertNull(memory.take(CapturedAudioHandle(3)))
    }

    @Test
    fun closeWipesTheKeptAndPendingCaptures() {
        val memory = EarMemory()
        val kept = captured(4)
        val pending = captured(5)
        memory.retain(kept)
        memory.retries.trySend(pending)
        memory.close()
        assertTrue(kept.samples.all { it.toInt() == 0 })
        assertTrue(pending.samples.all { it.toInt() == 0 })
        val late = captured(6)
        memory.retain(late)
        assertTrue(late.samples.all { it.toInt() == 0 })
    }

    @Test
    fun forgetWipes() {
        val memory = EarMemory()
        val kept = captured(7)
        memory.retain(kept)
        memory.forget()
        assertTrue(kept.samples.all { it.toInt() == 0 })
    }
}
