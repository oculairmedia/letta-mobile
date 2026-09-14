package com.letta.mobile.ui.mascot

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MascotSizeTierTest {
    @Test
    fun smallAvatarsDefaultToStill() {
        assertFalse(mascotPlaysLiveByDefault(22.dp))
        assertFalse(mascotPlaysLiveByDefault(44.dp))
        assertFalse(mascotPlaysLiveByDefault(55.dp))
    }

    @Test
    fun companionFloorAndAboveDefaultToLive() {
        assertTrue(mascotPlaysLiveByDefault(MASCOT_LIVE_MIN_SIZE))
        assertTrue(mascotPlaysLiveByDefault(56.dp))
        assertTrue(mascotPlaysLiveByDefault(64.dp))
        assertTrue(mascotPlaysLiveByDefault(220.dp))
    }
}
