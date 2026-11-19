package com.letta.mobile.data.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MediaPlaybackCoordinatorTest {
    private fun loaded() = FakeMediaPlayer().apply { load(MediaSource.Inline("AAAA", "audio/wav")) }

    @Test
    fun startingOneItemPausesTheOneBefore() {
        val coordinator = MediaPlaybackCoordinator()
        val first = loaded()
        val second = loaded()
        coordinator.play(first)
        coordinator.play(second)
        assertEquals(MediaPlaybackStatus.Paused, first.state.value.status)
        assertEquals(MediaPlaybackStatus.Playing, second.state.value.status)
        assertSame(second, coordinator.activePlayer)
    }

    @Test
    fun replayingTheSameItemDoesNotPauseIt() {
        val coordinator = MediaPlaybackCoordinator()
        val player = loaded()
        coordinator.play(player)
        coordinator.play(player)
        assertEquals(listOf("load", "play", "play"), player.commands)
    }

    @Test
    fun pauseAllKeepsThePositionAndForgetDropsTheReleasedPlayer() {
        val coordinator = MediaPlaybackCoordinator()
        val player = loaded()
        coordinator.play(player)
        player.advanceTo(12_000L)
        coordinator.pauseAll()
        assertEquals(MediaPlaybackStatus.Paused, player.state.value.status)
        assertEquals(12_000L, player.state.value.positionMs)
        coordinator.forget(player)
        assertNull(coordinator.activePlayer)
        coordinator.pauseAll()
    }
}
