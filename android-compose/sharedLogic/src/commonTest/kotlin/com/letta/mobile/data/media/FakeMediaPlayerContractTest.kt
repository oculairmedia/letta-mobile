package com.letta.mobile.data.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** The contract against the scripted fake, plus the loading behaviour adapters share with it. */
class FakeMediaPlayerContractTest : MediaPlayerContract() {
    override fun newLoadedPlayer(): MediaPlayer = FakeMediaPlayer().apply { load(MediaSource.Inline("AAAA", "audio/wav")) }

    override fun finishPlayback(player: MediaPlayer) = (player as FakeMediaPlayer).advanceTo(Long.MAX_VALUE)

    @Test
    fun playWhileLoadingStartsOnceLoaded() {
        val player = FakeMediaPlayer(loadsImmediately = false)
        player.load(MediaSource.Remote("https://host/a.mp3"))
        player.play()
        assertEquals(MediaPlaybackStatus.Loading, player.state.value.status)
        player.finishLoading()
        assertEquals(MediaPlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun sourcesNeverPrintTheirUrlOrBytes() {
        assertEquals("Remote(url=<redacted>)", MediaSource.Remote("https://host/a.mp3?token=secret").toString())
        assertEquals("Inline(mimeType=audio/wav, chars=8)", MediaSource.Inline("QUJDREVG", "audio/wav").toString())
    }

    @Test
    fun theUnsupportedPlayerReportsUnsupportedAndIgnoresPlayback() {
        val player = UnsupportedMediaPlayer()
        player.load(MediaSource.Remote("https://host/a.mp4"))
        player.play()
        assertEquals(MediaPlaybackStatus.Unsupported, player.state.value.status)
        player.release()
        assertEquals(MediaPlaybackStatus.Released, player.state.value.status)
    }
}
