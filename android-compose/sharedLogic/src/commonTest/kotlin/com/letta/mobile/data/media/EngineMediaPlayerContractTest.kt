package com.letta.mobile.data.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine-backed player against the shared contract, over a scripted engine, plus the engine
 * behaviours the contract cannot see: queued play, readings that change on their own, failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineMediaPlayerContractTest : MediaPlayerContract() {
    private val scope = TestScope()

    override fun newLoadedPlayer(): MediaPlayer = player(ScriptedMediaEngine()).apply { load(SOURCE) }

    override fun finishPlayback(player: MediaPlayer) {
        ((player as EngineMediaPlayer).engine as ScriptedMediaEngine).reachEnd()
        tick()
    }

    private fun player(engine: MediaEngine) = EngineMediaPlayer(engine, scope, TICK_MS)

    private fun tick() {
        scope.advanceTimeBy(TICK_MS)
        scope.runCurrent()
    }

    /** Loads [SOURCE] on an engine that loads later, queues a play, optionally pauses, then lets it load. */
    private fun queuedPlay(pauseBeforeLoad: Boolean): Pair<MediaPlayer, ScriptedMediaEngine> {
        val engine = ScriptedMediaEngine(loadsImmediately = false)
        val player = player(engine).apply { load(SOURCE) }
        player.play()
        assertEquals(MediaPlaybackStatus.Loading, player.state.value.status)
        if (pauseBeforeLoad) player.pause()
        engine.finishLoading()
        tick()
        return player to engine
    }

    @Test
    fun playWhileLoadingStartsOnceTheEngineHasLoaded() {
        val (player, engine) = queuedPlay(pauseBeforeLoad = false)
        assertEquals(MediaPlaybackStatus.Playing, player.state.value.status)
        assertEquals(listOf("open", "play"), engine.commands)
    }

    @Test
    fun pauseWhileLoadingCancelsTheQueuedPlay() {
        val (player, engine) = queuedPlay(pauseBeforeLoad = true)
        assertEquals(MediaPlaybackStatus.Ready, player.state.value.status)
        assertEquals(listOf("open"), engine.commands)
    }

    @Test
    fun thePositionFollowsTheEngineWhilePlaying() {
        val engine = ScriptedMediaEngine()
        val player = player(engine).apply { load(SOURCE) }
        player.play()
        engine.reading = engine.reading.copy(positionMs = 1_500L)
        tick()
        assertEquals(1_500L, player.state.value.positionMs)
    }

    @Test
    fun outputStoppedByTheSystemReadsAsPaused() {
        val engine = ScriptedMediaEngine()
        val player = player(engine).apply { load(SOURCE) }
        player.play()
        tick()
        engine.reading = engine.reading.copy(isPlaying = false)
        tick()
        assertEquals(MediaPlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun aSeekWhilePlayingStaysPlayingAndHoldsItsTargetUntilTheEngineCatchesUp() {
        val engine = ScriptedMediaEngine()
        val player = player(engine).apply { load(SOURCE) }
        player.play()
        engine.reading = engine.reading.copy(positionMs = 1_000L)
        tick()
        engine.lagsOnSeek = true
        player.seekTo(6_000L)
        engine.reading = engine.reading.copy(isPlaying = false, isLoading = true)
        tick()
        assertEquals(MediaPlaybackStatus.Playing, player.state.value.status)
        assertEquals(6_000L, player.state.value.positionMs)
    }

    @Test
    fun engineFailuresMapToFailedOrUnsupported() {
        val unsupported = ScriptedMediaEngine(loadsImmediately = false)
        val first = player(unsupported).apply { load(SOURCE) }
        unsupported.reading = MediaEngineReading(failure = MediaEngineFailure.Unsupported)
        tick()
        assertEquals(MediaPlaybackStatus.Unsupported, first.state.value.status)
        val failed = ScriptedMediaEngine(loadsImmediately = false)
        val second = player(failed).apply { load(SOURCE) }
        failed.reading = MediaEngineReading(failure = MediaEngineFailure.Failed)
        tick()
        second.play()
        assertEquals(MediaPlaybackStatus.Failed, second.state.value.status)
    }

    @Test
    fun mutingSilencesTheEngineAndReleaseDisposesIt() {
        val engine = ScriptedMediaEngine()
        val player = player(engine).apply { load(SOURCE) }
        player.setVolume(0.5f)
        player.setMuted(true)
        assertEquals(0f, engine.outputVolume)
        player.setMuted(false)
        assertEquals(0.5f, engine.outputVolume)
        player.release()
        player.release()
        assertEquals(1, engine.commands.count { it == "dispose" })
        assertEquals(MediaPlaybackStatus.Released, player.state.value.status)
    }

    @Test
    fun videoSizeComesFromTheEngine() {
        val engine = ScriptedMediaEngine()
        val player = player(engine).apply { load(SOURCE) }
        engine.reading = engine.reading.copy(videoWidth = 1_280, videoHeight = 720)
        player.play()
        assertEquals(1_280, player.state.value.videoWidth)
        assertEquals(720, player.state.value.videoHeight)
    }

    private companion object {
        const val TICK_MS = 100L
        val SOURCE = MediaSource.Remote("https://host/a.mp4")
    }
}
