package com.letta.mobile.data.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The behaviour every [MediaPlayer] adapter must show (see the port's KDoc). An adapter's test
 * extends this with [newLoadedPlayer] returning a player that has loaded a short playable source
 * and reached Ready, and [finishPlayback] driving it to its end.
 */
abstract class MediaPlayerContract {
    abstract fun newLoadedPlayer(): MediaPlayer

    abstract fun finishPlayback(player: MediaPlayer)

    @Test
    fun aLoadedPlayerIsReadyAtTheStartWithADuration() {
        val state = newLoadedPlayer().state.value
        assertEquals(MediaPlaybackStatus.Ready, state.status)
        assertEquals(0L, state.positionMs)
        assertNotNull(state.durationMs, "a loaded source reports its duration")
    }

    @Test
    fun playAndPauseMoveBetweenPlayingAndPaused() {
        val player = newLoadedPlayer()
        player.play()
        assertEquals(MediaPlaybackStatus.Playing, player.state.value.status)
        player.pause()
        assertEquals(MediaPlaybackStatus.Paused, player.state.value.status)
        player.pause()
        assertEquals(MediaPlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun seekClampsToTheDurationAndKeepsThePausedStatus() {
        val player = newLoadedPlayer()
        val duration = player.state.value.durationMs!!
        player.seekTo(duration * 2)
        assertEquals(duration, player.state.value.positionMs)
        player.seekTo(-1L)
        assertEquals(0L, player.state.value.positionMs)
        assertEquals(MediaPlaybackStatus.Ready, player.state.value.status)
    }

    @Test
    fun playingAfterTheEndStartsAgainFromTheBeginning() {
        val player = newLoadedPlayer()
        player.play()
        finishPlayback(player)
        assertEquals(MediaPlaybackStatus.Ended, player.state.value.status)
        player.play()
        assertEquals(MediaPlaybackStatus.Playing, player.state.value.status)
        assertEquals(0L, player.state.value.positionMs)
    }

    @Test
    fun volumeClampsAndMutingKeepsTheVolume() {
        val player = newLoadedPlayer()
        player.setVolume(2f)
        assertEquals(1f, player.state.value.volume)
        player.setVolume(0.4f)
        player.setMuted(true)
        assertEquals(0.4f, player.state.value.volume)
        assertTrue(player.state.value.muted)
    }

    @Test
    fun releaseIsFinalAndIdempotent() {
        val player = newLoadedPlayer()
        player.release()
        player.release()
        player.play()
        player.setMuted(true)
        assertEquals(MediaPlaybackStatus.Released, player.state.value.status)
        assertEquals(false, player.state.value.muted)
    }
}
