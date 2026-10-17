@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.AttentionInterest
import com.letta.mobile.avatar.core.AttentionKey
import com.letta.mobile.avatar.core.AttentionKind
import com.letta.mobile.avatar.core.GazeRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.2: [mascotAttention] registers a surface's window rect while it is composed
 * and enabled, and retracts it otherwise; [MascotLive] hands every published point to its entry's
 * gaze world.
 */
class MascotAttentionUiTest {
    private val key = AttentionKey("approval:req-1")

    @Test
    fun aSurfaceRegistersWhileComposedAndEnabled() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT)
        var shown by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        setContent {
            shell.Provide {
                Column {
                    if (shown) Box(Modifier.size(120.dp, 40.dp).mascotAttention(key, AttentionKind.APPROVAL, enabled = enabled))
                }
            }
        }
        waitForIdle()
        val published = assertNotNull(shell.registry.attention[key], "an entered surface publishes")
        assertEquals(AttentionKind.APPROVAL, published.kind)
        assertEquals(AttentionInterest.APPROVAL, published.interest, "the kind's own decay by default")
        assertFalse(published.rect.isEmpty)
        enabled = false
        waitForIdle()
        assertFalse(key in shell.registry.attention, "a disabled surface retracts")
        enabled = true
        waitForIdle()
        assertTrue(key in shell.registry.attention)
        shown = false
        waitForIdle()
        assertFalse(key in shell.registry.attention, "a disposed surface retracts")
    }

    @Test
    fun theLiveMascotsWorldCarriesThePublishedPoints() = runComposeUiTest {
        val host = CachingMascotHost()
        val shell = FakeMascotShell(AGENT, layerMounted = false, host = host)
        setContent {
            shell.Provide {
                MascotSeat(AGENT, MascotStage.COMPOSER_COMPANION, 40.dp, empty = {})
            }
        }
        waitForIdle()
        AttentionPublisher(shell.registry.attention, key)
            .publish(AttentionKind.APPROVAL, AttentionInterest.APPROVAL, GazeRect(300f, 40f, 500f, 120f))
        waitForIdle()
        val entry = assertNotNull(host.made[AGENT], "the seat drew a live mascot")
        assertNotNull(entry.gazeWorld.attentionOf(AttentionKind.APPROVAL), "the card reached the director's world")
        AttentionPublisher(shell.registry.attention, key).retract()
        waitForIdle()
        assertEquals(null, entry.gazeWorld.attentionOf(AttentionKind.APPROVAL), "and left it again")
    }

    private companion object {
        const val AGENT = "agent-attention"
    }
}
