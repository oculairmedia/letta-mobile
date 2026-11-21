@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.mascot

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.avatar.core.model.MascotShape
import com.letta.mobile.data.backend.bound
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentImportParams
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ImportedAgentsResponse
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.data.repository.CachedAgentRepository
import com.letta.mobile.data.repository.api.AgentRemoteSource
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A screen change must never show an agent's placeholder (the letter tile, a blank) for a frame
 * once its mascot is on screen. Both the outgoing and the incoming screen stay composed through the
 * whole transition and each draws the same agent through [AgentAvatar].
 *
 * The oracle reads pixels, not production helpers: every frame is captured and the colour at the
 * centre of each avatar tile says which branch drew it (a live scene, the still, the letter tile,
 * or nothing). The host is [AndroidMascotHost]'s semantics without Rive: the same entry table, the
 * same live-driver and still rules (app/.../AndroidMascotHost.kt:154-185), and the real
 * [MascotStills] over a store that answers like the app's cache directory does, off the UI thread.
 */
class MascotNavigationFlashUiTest {
    private enum class Branch {
        LIVE,
        STILL,
        LETTER_TILE,
        BLANK,
        ;

        val isMascot get() = this == LIVE || this == STILL
    }

    private val stillColor = Color(0xFFFF00FF)
    private val liveColor = Color(0xFF00FFFF)
    private val letterTileColor = Color(0xFF00FF00)
    private val identity = MascotIdentity(MascotShape.entries.first(), argb = 0xFF00AA88.toInt())

    /** [AndroidMascotHost] minus Rive: the host the shared code is written against. */
    private class AndroidLikeHost(
        private val liveColor: Color,
        override val stills: MascotStills,
    ) : MascotHost {
        private val entries = MascotEntries<MascotEntry> { identity -> newEntry(identity) }
        private val liveDrivers = mutableStateMapOf<MascotEntry, Any>()

        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry = entries.get(agentId, identity)!!

        private fun newEntry(identity: MascotIdentity): MascotEntry =
            object : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}, random = Random(0)) {
                override suspend fun load() = Unit
                override fun writeIdentity(identity: MascotIdentity) = Unit
                override fun dispose() = Unit
            }

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            val token = remember { Any() }
            val driver = liveDrivers[entry]
            val drives = playing && (driver == null || driver === token)
            DisposableEffect(entry, playing) {
                if (playing && liveDrivers[entry] == null) liveDrivers[entry] = token
                onDispose { if (liveDrivers[entry] === token) liveDrivers.remove(entry) }
            }
            var wasLive by remember { mutableStateOf(false) }
            if (drives) wasLive = true
            if (drives || wasLive) {
                Box(modifier.background(liveColor))
            } else {
                val identity = entry.identity
                LaunchedEffect(identity) { stills.ensure(identity) }
                stills.get(identity)?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit) }
            }
        }
    }

    private fun solid(color: Color): ImageBitmap = ImageBitmap(STILL_PX, STILL_PX).also { bitmap ->
        Canvas(bitmap).drawRect(0f, 0f, STILL_PX.toFloat(), STILL_PX.toFloat(), Paint().apply { this.color = color })
    }

    /** The real [MascotStills]; its saved stills come back after a short simulated disk read, as in the app. */
    private fun stills() = MascotStills(
        assetVersion = "nav-test",
        store = object : MascotStillStore {
            override suspend fun read(key: String): ByteArray {
                delay(DISK_READ_MS)
                return byteArrayOf(1)
            }

            override suspend fun write(key: String, png: ByteArray) = Unit
        },
        capture = { byteArrayOf(2) },
        decode = { solid(stillColor) },
    )

    private fun registryOf(vararg ids: String) = MascotIdentityRegistry().also { r -> r.update(ids.associateWith { identity }) }

    /** Two screens that each draw [agent]'s avatar, cross-sliding when [screen] changes. */
    private fun ComposeUiTest.mount(
        host: MascotHost,
        registry: MascotIdentityRegistry,
        agent: String = AGENT,
        reducedMotion: Boolean = false,
        beforeScreens: @Composable () -> Unit = {},
    ): MutableState<Int> {
        val screen = mutableStateOf(0)
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(secondaryContainer = letterTileColor)) {
                CompositionLocalProvider(
                    LocalMascotHost provides host,
                    LocalMascotRegistry provides registry,
                    LocalReducedMotion provides reducedMotion,
                ) {
                    beforeScreens()
                    AnimatedContent(
                        targetState = screen.value,
                        transitionSpec = {
                            slideInHorizontally(tween(TRANSITION_MS)) { it } togetherWith slideOutHorizontally(tween(TRANSITION_MS)) { -it }
                        },
                        modifier = Modifier.size(SCREEN_DP.dp),
                        label = "screens",
                    ) { page ->
                        Column(Modifier.fillMaxSize()) {
                            AgentAvatar(agentId = agent, name = "Meridian", size = TILE_DP.dp, modifier = Modifier.testTag("avatar-$page"))
                        }
                    }
                }
            }
        }
        return screen
    }

    /** Which branch drew each avatar tile that is on screen this frame. */
    private fun ComposeUiTest.drawnBranches(): List<Pair<String, Branch>> {
        val image = onRoot().captureToImage().toPixelMap()
        return listOf("avatar-0", "avatar-1").flatMap { tag ->
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
                .fetchSemanticsNodes()
                // boundsInRoot is clipped to the window: a page still sliding in or already gone is empty.
                .filter { !it.boundsInRoot.isEmpty }
                .map { node ->
                    val centre = node.boundsInRoot.center
                    tag to when (image[centre.x.toInt(), centre.y.toInt()].toArgb()) {
                        stillColor.toArgb() -> Branch.STILL
                        liveColor.toArgb() -> Branch.LIVE
                        letterTileColor.toArgb() -> Branch.LETTER_TILE
                        else -> Branch.BLANK
                    }
                }
        }
    }

    /** Advances [frames] frames; every visible tile that is not the mascot, as "frame n tag -> branch". */
    private fun ComposeUiTest.placeholders(frames: Int, from: Int = 0, beforeFrame: (Int) -> Unit = {}): List<String> =
        (from until from + frames).flatMap { frame ->
            beforeFrame(frame)
            mainClock.advanceTimeByFrame()
            drawnBranches().filter { !it.second.isMascot }.map { (tag, branch) -> "frame $frame $tag -> $branch" }
        }

    private fun ComposeUiTest.warmUp(frames: Int = WARM_FRAMES) = repeat(frames) { mainClock.advanceTimeByFrame() }

    @Test
    fun anIdleAgentsAvatarSurvivesAScreenChange() = runComposeUiTest {
        val screen = mount(AndroidLikeHost(liveColor, stills()), registryOf(AGENT))
        warmUp()
        assertTrue(drawnBranches().all { it.second.isMascot }, "the mascot is up before the change")
        screen.value = 1
        assertEquals(emptyList(), placeholders(TRANSITION_FRAMES))
    }

    @Test
    fun aWorkingAgentsAvatarSurvivesAScreenChange() = runComposeUiTest {
        val registry = registryOf(AGENT).also { it.setPresence(AGENT, AgentPresence(activity = AgentActivityKind.THINKING)) }
        val screen = mount(AndroidLikeHost(liveColor, stills()), registry)
        warmUp()
        screen.value = 1
        assertEquals(emptyList(), placeholders(TRANSITION_FRAMES))
    }

    /**
     * An agent that has only ever been drawn live (a long run) has no still yet: nothing asked for
     * one while it moved. When its run ends the avatar swaps live -> still, and a still that has to be
     * read from disk first is an empty tile for those frames.
     */
    @Test
    fun anAvatarWhoseRunEndsNeverGoesBlankWaitingForItsStill() = runComposeUiTest {
        val registry = registryOf(AGENT).also { it.setPresence(AGENT, AgentPresence(activity = AgentActivityKind.THINKING)) }
        val screen = mount(AndroidLikeHost(liveColor, stills()), registry, reducedMotion = true)
        warmUp()
        assertTrue(drawnBranches().all { it.second == Branch.LIVE }, "the working agent is live: ${drawnBranches()}")
        registry.setPresence(AGENT, AgentPresence(activity = AgentActivityKind.IDLE))
        screen.value = 1
        assertEquals(emptyList(), placeholders(TRANSITION_FRAMES))
    }

    /** What the roster's remote answers: pages by offset and limit, and can hold one page back. */
    private class PagedRemote(private val roster: List<Agent>) : AgentRemoteSource {
        var heldPage: Pair<Int, CompletableDeferred<Unit>>? = null

        override suspend fun listAgents(limit: Int?, offset: Int?, tags: List<String>?): List<Agent> {
            val start = offset ?: 0
            heldPage?.takeIf { (heldOffset, _) -> heldOffset == start }?.second?.await()
            return roster.drop(start).take(limit ?: roster.size)
        }

        override suspend fun listAgentsSlim(limit: Int?, offset: Int?, tags: List<String>?): List<AgentSummary> = error("unused")
        override suspend fun getAgent(agentId: AgentId): Agent = error("unused")
        override suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview = error("unused")
        override suspend fun countAgents(): Int = roster.size
        override suspend fun createAgent(params: AgentCreateParams): Agent = error("unused")
        override suspend fun updateAgent(agentId: AgentId, params: AgentUpdateParams): Agent = error("unused")
        override suspend fun deleteAgent(agentId: AgentId) = error("unused")
        override suspend fun exportAgent(agentId: AgentId): String = error("unused")
        override suspend fun importAgent(params: AgentImportParams): ImportedAgentsResponse = error("unused")
        override suspend fun attachArchive(agentId: AgentId, archiveId: String) = error("unused")
        override suspend fun detachArchive(agentId: AgentId, archiveId: String) = error("unused")
    }

    /**
     * The app refreshes the roster whenever the workspace is composed (AndroidShellWorkspace's
     * `drawerModel.refresh(roster = true)`), and the repository publishes each page as it lands
     * (CachedAgentRepository.startRefreshLocked -> publishRosterLocked): with more agents than one
     * page, the roster the shell collects is the first page alone until the next page answers. The
     * shell mirrors it into the registry (MascotIdentitySync), so an agent past the first page loses
     * its identity - and its mascot - for that long.
     */
    @Test
    fun aRosterRefreshMidTransitionNeverTurnsAnAvatarIntoItsLetterTile() = runComposeUiTest {
        val everyone = (0 until ROSTER_SIZE).map { Agent(id = AgentId("agent-$it"), name = "Agent $it") }
        val remote = PagedRemote(everyone)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = CachedAgentRepository(remote.bound(), repositoryScope = scope)
            scope.launch { repository.refreshAgents() }
            assertEquals(ROSTER_SIZE, repository.agents.value.size, "the roster is fully known before the refresh")

            val registry = MascotIdentityRegistry()
            val screen = mount(AndroidLikeHost(liveColor, stills()), registry, agent = PAST_FIRST_PAGE) {
                // MascotIdentitySync (app/.../MascotIdentitySync.kt): the roster, mirrored into the registry.
                val roster by repository.agents.collectAsState()
                LaunchedEffect(roster) {
                    registry.update(roster.associate { agent -> agent.id.value to resolveMascotIdentity(agent.id.value, agent, null) })
                }
            }
            warmUp()
            assertTrue(drawnBranches().all { it.second.isMascot }, "the mascot is up before the change: ${drawnBranches()}")

            // Navigate; the workspace refreshes the roster as it opens. The second page is slow.
            val secondPage = CompletableDeferred<Unit>()
            remote.heldPage = PAGE_SIZE to secondPage
            screen.value = 1
            scope.launch { repository.refreshAgents() }
            assertEquals(PAGE_SIZE, repository.agents.value.size, "the first page is published alone")

            val flashes = placeholders(TRANSITION_FRAMES) { frame -> if (frame == PAGE_ARRIVES_AT_FRAME) secondPage.complete(Unit) }
            assertEquals(ROSTER_SIZE, repository.agents.value.size, "the refresh finished")
            assertEquals(emptyList(), flashes)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val AGENT = "agent-nav"
        const val STILL_PX = 8
        const val SCREEN_DP = 300
        const val TILE_DP = 64
        const val TRANSITION_MS = 320
        const val TRANSITION_FRAMES = 30
        const val WARM_FRAMES = 40
        const val DISK_READ_MS = 80L
        const val PAGE_SIZE = 50
        const val ROSTER_SIZE = 101
        const val PAST_FIRST_PAGE = "agent-80"
        const val PAGE_ARRIVES_AT_FRAME = 12
    }
}
