@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotSemantics
import com.letta.mobile.ui.shell.rail.ShellAgentRail
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellAgentRailState
import com.letta.mobile.ui.shell.rail.ShellAgentRailTags
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.rail.ShellRailMapping
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanel
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelTags
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The drawer pictures an agent one way (letta-mobile-c3np7.5.8 device report: the panel's hero was
 * a different shape from the agent's orb). On a phone there is no transport layer, so the hero is
 * drawn in place and must follow the same live-or-still rule as the rail orb and every avatar.
 */
class ShellDrawerMascotTest {
    private val turned = MascotIdentity(MascotShape.HEXAGON, argb = 0xFFF08A3C.toInt(), rotationDegrees = 45)

    private fun shell(): FakeMascotShell =
        FakeMascotShell(AGENT, layerMounted = false).also { it.registry.identities[AGENT] = turned }

    private val railEntry = ShellRailMapping.entries(
        ShellRailMapping.groups(listOf(AGENT to "PM-letta-mobile"), selectedAgentId = null),
        ShellRailFocus(selectedAgentId = null, identityByAgentId = mapOf(AGENT to turned)),
    ).single()

    private fun ComposeUiTest.drawer(shell: FakeMascotShell) {
        setContent {
            shell.Provide {
                MaterialTheme {
                    Row {
                        ShellAgentRail(state = ShellAgentRailState(entries = listOf(railEntry)), actions = ShellAgentRailActions())
                        ShellAgentPanel(
                            state = ShellAgentPanelState(agent = ShellPanelAgent(name = "PM-letta-mobile", agentId = AGENT, identity = turned)),
                            actions = ShellAgentPanelActions(),
                            modifier = Modifier.width(280.dp).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.mascotUnder(tag: String): SemanticsNode =
        onNode(SemanticsMatcher.keyIsDefined(MascotSemantics.Identity) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNode()

    @Test
    fun theIdleHeroIsTheSamePictureAsTheAgentsRailOrb() = runComposeUiTest {
        drawer(shell())
        val hero = mascotUnder(ShellAgentPanelTags.HERO)
        val orb = mascotUnder(ShellAgentRailTags.orb(railEntry.key))
        // Same shape, colour and turn, and both the identity's still - not a fresh live scene.
        assertEquals(turned, hero.config[MascotSemantics.Identity])
        assertEquals(orb.config[MascotSemantics.Identity], hero.config[MascotSemantics.Identity])
        assertEquals(false, hero.config[MascotSemantics.Live])
        assertEquals(orb.config[MascotSemantics.Live], hero.config[MascotSemantics.Live])
    }

    @Test
    fun theHeroMovesWhileTheAgentWorksLikeEveryAvatarDoes() = runComposeUiTest {
        val shell = shell()
        shell.registry.setPresence(AGENT, AgentPresence(activity = AgentActivityKind.THINKING))
        // A live mascot ticks every frame, so the clock never idles on its own.
        mainClock.autoAdvance = false
        drawer(shell)
        mainClock.advanceTimeByFrame()
        val hero = mascotUnder(ShellAgentPanelTags.HERO)
        assertEquals(turned, hero.config[MascotSemantics.Identity])
        assertEquals(true, hero.config[MascotSemantics.Live])
    }

    private companion object {
        const val AGENT = "agent-pm"
    }
}
