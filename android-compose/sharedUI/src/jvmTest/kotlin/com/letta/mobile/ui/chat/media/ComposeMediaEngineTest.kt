package com.letta.mobile.ui.chat.media

import com.letta.mobile.data.media.EngineMediaPlayer
import com.letta.mobile.data.media.JavaSoundMediaPlayer
import com.letta.mobile.data.media.MediaEngineFailure
import com.letta.mobile.data.media.MediaKind
import com.letta.mobile.data.media.MediaPlaybackStatus
import com.letta.mobile.data.media.MediaSource
import com.letta.mobile.data.media.UnsupportedMediaPlayer
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import java.util.concurrent.Executors
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ComposeMediaPlayer binding: its reading of the library's state on every runner, and real
 * playback of a two-second test pattern where the desktop engine loads (skipped elsewhere, such as a
 * Linux runner without GStreamer, where the open-elsewhere fallback is checked instead).
 */
class ComposeMediaEngineTest {
    // One thread for the player, as the composition's main thread is in the app.
    private val executor = Executors.newSingleThreadExecutor()
    private val playerThread = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + playerThread)

    @AfterTest
    fun stopPlayerThread() {
        scope.cancel()
        executor.shutdown()
    }

    private fun <T> onPlayerThread(block: () -> T): T = runBlocking(playerThread) { block() }

    @Test
    fun theLibraryStateReadsInMillisecondsWithCodecErrorsUnsupported() {
        val engine = ComposeMediaEngine(
            PreviewableVideoPlayerState(currentTime = 1.5, duration = 10.0, isPlaying = true, error = VideoPlayerError.CodecError("x")),
        )
        val reading = engine.read()
        assertEquals(1_500L, reading.positionMs)
        assertEquals(10_000L, reading.durationMs)
        assertTrue(reading.isPlaying)
        assertEquals(MediaEngineFailure.Unsupported, reading.failure)
        val failed = ComposeMediaEngine(PreviewableVideoPlayerState(duration = 0.0, error = VideoPlayerError.NetworkError("x")))
        assertNull(failed.read().durationMs)
        assertEquals(MediaEngineFailure.Failed, failed.read().failure)
    }

    @Test
    fun desktopAudioStaysOnJavaSoundAndVideoFallsBackWithoutTheNativeBridge() {
        assertIs<JavaSoundMediaPlayer>(platformMediaPlayer(MediaKind.Audio, scope))
        val video = platformMediaPlayer(MediaKind.Video, scope)
        if (video != null) assertIs<UnsupportedMediaPlayer>(video)
    }

    @Test
    fun aRealClipLoadsPlaysSeeksAndEndsThroughTheDesktopEngine() {
        assumeTrue("the desktop engine's native bridge does not load here", platformMediaPlayer(MediaKind.Video, scope) == null)
        val player = onPlayerThread { EngineMediaPlayer(ComposeMediaEngine(createVideoPlayerState()), scope) }
        onPlayerThread { player.load(MediaSource.Inline(Base64.getEncoder().encodeToString(clip()), "video/mp4")) }
        awaitStatus(player, MediaPlaybackStatus.Ready)
        assertEquals(CLIP_MS, player.state.value.durationMs)
        assertEquals(CLIP_WIDTH, player.state.value.videoWidth)

        onPlayerThread { player.play() }
        awaitThat("the clock advances") { player.state.value.positionMs > 0L }
        onPlayerThread { player.pause() }
        assertEquals(MediaPlaybackStatus.Paused, player.state.value.status)
        onPlayerThread { player.seekTo(CLIP_MS / 2) }
        awaitThat("the engine reaches the seek target") { (player.engine.read().positionMs - CLIP_MS / 2) in -SEEK_SLACK_MS..SEEK_SLACK_MS }

        onPlayerThread { player.play() }
        awaitStatus(player, MediaPlaybackStatus.Ended)
        onPlayerThread { player.play() }
        assertEquals(MediaPlaybackStatus.Playing, player.state.value.status)
        assertEquals(0L, player.state.value.positionMs)
        onPlayerThread { player.release() }
        assertEquals(MediaPlaybackStatus.Released, player.state.value.status)
    }

    @Test
    fun bytesTheDesktopEngineCannotOpenFail() {
        assumeTrue("the desktop engine's native bridge does not load here", platformMediaPlayer(MediaKind.Video, scope) == null)
        val player = onPlayerThread { EngineMediaPlayer(ComposeMediaEngine(createVideoPlayerState()), scope) }
        onPlayerThread { player.load(MediaSource.Inline(Base64.getEncoder().encodeToString("not a video".toByteArray()), "video/mp4")) }
        awaitStatus(player, MediaPlaybackStatus.Failed)
        onPlayerThread { player.release() }
    }

    private fun clip(): ByteArray = checkNotNull(javaClass.getResourceAsStream("/media/test-pattern.mp4")).use { it.readBytes() }

    private fun awaitStatus(player: EngineMediaPlayer, status: MediaPlaybackStatus) =
        awaitThat("status $status (now ${player.state.value.status})") { player.state.value.status == status }

    private fun awaitThat(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_NS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val CLIP_MS = 2_000L
        const val CLIP_WIDTH = 160
        const val SEEK_SLACK_MS = 300L
        const val POLL_MS = 20L
        const val TIMEOUT_NS = 15_000_000_000L
    }
}
