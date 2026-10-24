@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ShellWorkspaceRememberingTest {
    private data class Inputs(
        val label: String,
        val session: String,
        val onAgentSelected: (String) -> Unit,
    )

    @Test
    fun retainedHostReadsCurrentComposeInputsAndCallbacks() = runComposeUiTest {
        val shellIdentity = Any()
        val drawer = ShellWorkspaceDrawer()
        val calls = mutableListOf<String>()
        val inputs = mutableStateOf(Inputs("model-one", "session-one") { calls += "one:$it" })
        val unrelatedTick = mutableStateOf(0)
        var builds = 0
        var observedHost: LettaShellWorkspaceHost? = null

        setContent {
            val current = rememberShellWorkspaceHost(inputs.value, shellIdentity to inputs.value.session) { latest ->
                builds++
                LettaShellWorkspaceHost(
                    actions = ShellWorkspaceNavigationActions(
                        rail = ShellAgentRailActions(onAgentSelected = { latest.value.onAgentSelected(it) }),
                    ),
                    slots = LettaShellWorkspaceSlots(
                        main = { modifier -> Text(latest.value.label, modifier.testTag("remembered-host-slot")) },
                        agentCard = {},
                        sidePane = {},
                        overlays = {},
                    ),
                    chrome = ShellWindowChrome.Inline,
                    drawer = drawer,
                    events = ShellWorkspaceEvents(),
                )
            }
            SideEffect { observedHost = current }
            Column {
                Text("tick-${unrelatedTick.value}")
                current.slots.main(Modifier)
            }
        }

        lateinit var firstHost: LettaShellWorkspaceHost
        runOnIdle {
            firstHost = checkNotNull(observedHost)
            assertEquals(1, builds)
        }
        runOnIdle { unrelatedTick.value++ }
        assertSame(firstHost, observedHost, "unrelated recompositions keep the workspace host")
        assertEquals(1, builds, "the host builder does not run for unrelated ticks")

        runOnIdle { inputs.value = Inputs("model-two", "session-one") { calls += "two:$it" } }
        onNodeWithTag("remembered-host-slot").assertTextEquals("model-two")
        assertSame(firstHost, observedHost, "model changes update the retained host's state")
        runOnIdle { firstHost.actions.rail.onAgentSelected("agent-a") }
        assertEquals("two:agent-a", calls.last(), "an existing action reads its latest callback")

        runOnIdle { inputs.value = Inputs("model-three", "session-two") { calls += "three:$it" } }
        onNodeWithTag("remembered-host-slot").assertTextEquals("model-three")
        val secondHost = checkNotNull(observedHost)
        assertNotSame(firstHost, secondHost, "a session switch gets a host for the new identity")
        runOnIdle { secondHost.actions.rail.onAgentSelected("agent-b") }
        assertEquals("three:agent-b", calls.last())
    }
}
