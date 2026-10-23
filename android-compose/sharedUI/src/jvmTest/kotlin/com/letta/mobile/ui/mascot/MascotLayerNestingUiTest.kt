@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Android mounts a transport layer at the window root around the shared workspace, which mounts its
 * own: only the outer one draws, or every character stands on its seat twice.
 */
class MascotLayerNestingUiTest {
    private fun ComposeUiTest.characters(): Int =
        onAllNodes(SemanticsMatcher.keyIsDefined(MascotSemantics.Identity), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun aLayerInsideAnotherDrawsNoSecondCharacter() = runComposeUiTest {
        val shell = FakeMascotShell(AGENT, layerMounted = false, followsSeatRule = true)
        shell.registry.setPresence(AGENT, AgentPresence(activity = AgentActivityKind.THINKING))
        // A live mascot ticks every frame, so the clock never idles on its own.
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                DriveMascotStage(AgentId(AGENT), agentPaneVisible = false)
                MascotTransportLayer(reducedMotion = true) {
                    MascotTransportLayer(reducedMotion = true) {
                        Box(Modifier.size(400.dp, 800.dp)) {
                            MascotSeat(
                                AGENT,
                                MascotStage.COMPOSER_COMPANION,
                                size = 72.dp,
                                modifier = Modifier.offset(200.dp, 500.dp),
                                empty = {},
                            )
                        }
                    }
                }
            }
        }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }

        assertEquals(1, characters(), "one character, drawn by the outer layer only")
    }

    private companion object {
        const val AGENT = "agent-nested-layer"
        const val SETTLE_FRAMES = 30
    }
}
