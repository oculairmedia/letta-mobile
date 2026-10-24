@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.rail

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotIdentityRegistry
import com.letta.mobile.ui.mascot.MascotStillStore
import com.letta.mobile.ui.mascot.MascotStills
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scene requests for a test host: a still must use its cache and a live orb must request an entry. */
class ShellRailMascotStillUiTest {
    private val agentId = "agent-0"
    private val identity = MascotIdentity(MascotShape.HEXAGON, argb = 0xFF3FA0F0.toInt())

    private class CountingMascotHost(override val stills: MascotStills) : MascotHost {
        var entryRequests = 0
        var surfaceCalls = 0
        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry {
            entryRequests++
            return object : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}) {
                override suspend fun load() = Unit
                override fun writeIdentity(identity: MascotIdentity) = Unit
                override fun dispose() = Unit
            }
        }

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            surfaceCalls++
            Box(modifier.testTag("counting-live-mascot"))
        }
    }

    @Test
    fun oneHundredAgentRailUsesCachedStillsWithoutDrawingLiveMascots() = runComposeUiTest {
        val storeReads = intArrayOf(0)
        val captures = intArrayOf(0)
        val stills = cachedStills(storeReads, captures)
        runBlocking { stills.ensure(identity) }
        assertEquals(1, storeReads[0], "the sample mascot still is loaded from the cache")
        assertEquals(0, captures[0], "a cached still does not start a capture")

        val host = CountingMascotHost(stills)
        val registry = MascotIdentityRegistry().also {
            it.update(mapOf(agentId to identity))
            it.setPresence(agentId, AgentPresence(activity = AgentActivityKind.THINKING))
        }
        val roster = (0 until 100).map { index -> "agent-$index" to "Agent ${index.toString().padStart(3, '0')}" }
        val focus = ShellRailFocus(
            thinkingAgentId = agentId,
            identityByAgentId = mapOf(agentId to identity),
        )
        var expanded by mutableStateOf(false)
        var showLiveOrb by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMascotHost provides host,
                    LocalMascotRegistry provides registry,
                ) {
                    if (showLiveOrb) {
                        AgentOrb(index = 0, size = 32.dp, agentId = agentId)
                    } else {
                        ShellLibraryRail(
                            state = ShellLibraryRailState(roster, focus, expanded = expanded),
                            actions = ShellAgentRailActions(),
                        )
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(8, collapsedRailEntries(roster, focus).size, "100 agents feed at most eight collapsed entries")
        assertEquals(0, host.surfaceCalls, "collapsed rail tiles never draw live mascot scenes")
        assertEquals(0, captures[0])
        onNodeWithTag("shell-thinking-ring-Agent 000").assertExists()

        expanded = true
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(0, host.surfaceCalls, "expanded library tiles use the same still-only tile")
        assertEquals(0, captures[0])

        // Same harness and host: an ordinary AgentOrb still follows the active-agent live default.
        val entriesBeforeLiveOrb = host.entryRequests
        showLiveOrb = true
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(
            host.entryRequests > entriesBeforeLiveOrb,
            "the active ordinary orb requests a live entry " +
                "(before=$entriesBeforeLiveOrb, after=${host.entryRequests}, surfaces=${host.surfaceCalls})",
        )
        assertTrue(host.surfaceCalls > 0, "the live control reaches the host renderer")
    }

    private fun cachedStills(storeReads: IntArray, captures: IntArray) = MascotStills(
        assetVersion = "rail-test",
        store = object : MascotStillStore {
            override suspend fun read(key: String): ByteArray {
                storeReads[0]++
                return byteArrayOf(1)
            }

            override suspend fun write(key: String, png: ByteArray) = Unit
        },
        capture = { captures[0]++; byteArrayOf(2) },
        decode = { ImageBitmap(2, 2) },
    )
}
