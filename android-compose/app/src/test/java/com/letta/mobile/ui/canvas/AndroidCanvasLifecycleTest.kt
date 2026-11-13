package com.letta.mobile.ui.canvas

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.ui.test.setLettaTestContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.MainActivityDependencies
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.canvas.boards.LocalCanvasBoardSessions
import com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry
import com.letta.mobile.ui.screens.canvas.AndroidCanvasHost
import com.letta.mobile.ui.screens.canvas.CanvasViewModel
import com.letta.mobile.ui.screens.canvas.rememberCanvasPageState
import dagger.Lazy
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real Android host/page bindings, with retained ViewModelStore ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidCanvasLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private val backing = InMemoryCanvasDocumentStore()
    private var reads = 0
    private var failReads = false
    private val store = object : CanvasDocumentStore by backing {
        override suspend fun get(id: CanvasId): CanvasDocument? {
            reads++
            check(!failReads) { "Temporary canvas read failure" }
            return backing.get(id)
        }
    }
    private val factory = Provider { CanvasBoardSessions(store) }
    private val retained = com.letta.mobile.ui.screens.canvas.AndroidCanvasBoards(factory, mockk(relaxed = true))
    private val deps = MainActivityDependencies(
        lazySettingsRepository = mockk(), crashReporter = mockk(), lazyAgentRepository = mockk(),
        lazySecureSettingsStore = mockk(), lazyConversationRunRegistry = mockk(), lazyAttentionPrefs = mockk(),
        lazyAgentDefaults = mockk(), lazyCanvasBoards = Lazy { retained },
        lazyCanvasViewers = Lazy { CanvasDocumentViewerRegistry() }, lazySubagentRepository = mockk(),
        lazySharedNotebooks = mockk(),
    )

    @Test
    fun configLoadingAndSavesPreserveNavigationAndOwnerButBackendSwitchReleasesOldOwner() {
        var config by mutableStateOf<LettaConfig?>(null)
        var navigation: NavHostController? = null
        var boards: CanvasBoardSessions? = null
        compose.setContent {
            AndroidCanvasHost(deps, config) {
                val controller = rememberNavController()
                val owner = LocalCanvasBoardSessions.current
                SideEffect { navigation = controller; boards = owner }
                NavHost(controller, startDestination = FirstPage) {
                    composable(FirstPage) {}
                    composable(SecondPage) {}
                }
            }
        }
        compose.waitForIdle()
        val firstNavigation = navigation
        val firstBoards = checkNotNull(boards)
        compose.runOnIdle { navigation!!.navigate(SecondPage) }
        config = backend()
        compose.waitForIdle()
        assertSame("loading configuration must not recreate navigation", firstNavigation, navigation)
        assertSame("the first loaded identity adopts the initial owner", firstBoards, boards)
        assertEquals(SecondPage, navigation!!.currentDestination?.route)
        config = backend().copy(parkedServerUrl = "https://parked.invalid", accessToken = "replacement")
        compose.waitForIdle()
        assertSame(firstNavigation, navigation)
        assertSame(firstBoards, boards)
        assertEquals(SecondPage, navigation!!.currentDestination?.route)
        val page = runBlocking { firstBoards.acquirePage(null, null, null) }
        val dispose = firstBoards.registry.registerMount(page)
        config = backend().copy(id = OtherBackend)
        compose.waitForIdle()
        assertSame("changing a backend must not recreate navigation either", firstNavigation, navigation)
        assertEquals(SecondPage, navigation!!.currentDestination?.route)
        assertNotSame(firstBoards, boards)
        assertEquals(0, firstBoards.holds(page.canvasId))
        assertEquals(null, firstBoards.registry.get(page.canvasId))
        dispose()
    }

    @Test
    fun configurationRecreationKeepsThePageHoldAndDoesNotReadAgain() {
        val page = runBlocking { CanvasSession.create(backing, CanvasCreateOptions()) }
        assertEquals(page.canvasId, exerciseRetainedPage(page.canvasId).canvasId)
    }

    @Test
    fun anUntitledPageKeepsOneGeneratedBoardAcrossRecreation() {
        org.junit.Assert.assertTrue(exerciseRetainedPage(null).canvasId.value.isNotBlank())
    }

    @Test
    fun acquisitionFailureShowsTheSharedRetrySurfaceAndRetries() {
        val page = runBlocking { CanvasSession.create(backing, CanvasCreateOptions()) }
        val viewModels = ViewModelStore()
        val model = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                CanvasViewModel(mockk(relaxed = true), mockk(), mockk(), CanvasDocumentViewerRegistry(), retained),
            )!!
        })[CanvasViewModel::class.java]
        failReads = true
        compose.setLettaTestContent {
            AndroidCanvasHost(deps, backend()) {
                com.letta.mobile.ui.screens.canvas.CanvasScreen(page.canvasId.value, onNavigateBack = null, viewModel = model)
            }
        }
        compose.waitUntil { reads == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("Retry").assertExists().performClick()
        compose.waitUntil { reads == 2 }
        compose.waitForIdle()
        compose.onNodeWithText("Retry").assertExists()
        assertEquals(0, retained.boards.value.holds(page.canvasId))
        compose.runOnIdle { viewModels.clear() }
    }

    @Test
    fun aClosedFullscreenPagePopsWithoutRebuildingNavigation() {
        val page = runBlocking { CanvasSession.create(backing, CanvasCreateOptions()) }
        val viewModels = ViewModelStore()
        val model = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                CanvasViewModel(mockk(relaxed = true), mockk(), mockk(), CanvasDocumentViewerRegistry(), retained),
            )!!
        })[CanvasViewModel::class.java]
        var config by mutableStateOf(backend())
        var navigation: NavHostController? = null
        failReads = true
        compose.setLettaTestContent {
            AndroidCanvasHost(deps, config) {
                val controller = rememberNavController()
                SideEffect { navigation = controller }
                NavHost(controller, startDestination = FirstPage) {
                    composable(FirstPage) {}
                    composable(SecondPage) {
                        com.letta.mobile.ui.screens.canvas.CanvasScreen(
                            page.canvasId.value, onNavigateBack = { controller.popBackStack() }, viewModel = model,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val firstNavigation = navigation
        compose.runOnIdle { navigation!!.navigate(SecondPage) }
        compose.waitUntil { reads == 1 }
        config = backend().copy(id = OtherBackend)
        compose.waitForIdle()
        assertSame(com.letta.mobile.data.canvas.library.CanvasPageState.Closed, model.state.value)
        compose.waitUntil(timeoutMillis = NavigationTimeoutMillis) { navigation!!.currentDestination?.route == FirstPage }
        assertSame(firstNavigation, navigation)
        assertEquals("the old page must not acquire under the new backend", 1, reads)
        assertEquals(0, retained.boards.value.holds(page.canvasId))
        compose.runOnIdle { viewModels.clear() }
    }

    private fun exerciseRetainedPage(canvasId: CanvasId?): CanvasSession {
        val owner = factory.get()
        val retainedOwner = com.letta.mobile.ui.screens.canvas.AndroidCanvasBoards(Provider { owner }, mockk(relaxed = true))
        val viewModels = ViewModelStore()
        val model = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                CanvasViewModel(mockk(relaxed = true), mockk(), mockk(), CanvasDocumentViewerRegistry(), retainedOwner),
            )!!
        })[CanvasViewModel::class.java]
        var mounted by mutableStateOf(true)
        var currentBoards by mutableStateOf(owner)
        var shown: CanvasSession? = null
        compose.setContent {
            if (mounted) {
                val session = rememberCanvasPageState(currentBoards, model, canvasId, null, null)
                SideEffect { shown = (session as? com.letta.mobile.data.canvas.library.CanvasPageState.Ready)?.session }
            }
        }
        compose.waitUntil { shown != null }
        val first = checkNotNull(shown)
        val firstReads = reads
        assertEquals(1, owner.holds(first.canvasId))
        mounted = false
        compose.waitForIdle()
        assertEquals("configuration teardown must retain the page hold", 1, owner.holds(first.canvasId))
        mounted = true
        compose.waitForIdle()
        assertSame(first, shown)
        assertEquals(firstReads, reads)
        // The root can observe a new owner before the retained ViewModel's collector catches up.
        currentBoards = factory.get()
        compose.waitForIdle()
        assertEquals("an old page must never mount in the replacement owner's registry", null, shown)
        compose.runOnIdle { viewModels.clear() }
        compose.waitForIdle()
        assertEquals(0, owner.holds(first.canvasId))
        assertEquals(null, owner.registry.get(first.canvasId))
        return first
    }

    @Test
    fun retainedConversationKeysSelectFreshModelsAcrossBackendRoundTrips() {
        val viewModels = ViewModelStore()
        val provider = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                CanvasViewModel(mockk(relaxed = true), mockk(), mockk(), CanvasDocumentViewerRegistry(), retained),
            )!!
        })
        val conversation = com.letta.mobile.data.model.ConversationId("host-local-conversation")
        var config by mutableStateOf(backend())
        var model: CanvasViewModel? = null
        var shown: CanvasSession? = null
        compose.setContent {
            AndroidCanvasHost(deps, config) {
                val table = checkNotNull(LocalCanvasBoardSessions.current)
                val generation = com.letta.mobile.ui.canvas.boards.LocalCanvasBoardGeneration.current
                val key = checkNotNull(com.letta.mobile.ui.chat.surface.chatCanvasKey(conversation.value, generation))
                val current = provider[key, CanvasViewModel::class.java]
                val state = rememberCanvasPageState(table, current, null, conversation, null)
                SideEffect {
                    model = current
                    shown = (state as? com.letta.mobile.data.canvas.library.CanvasPageState.Ready)?.session
                }
            }
        }
        compose.waitUntil(timeoutMillis = NavigationTimeoutMillis) { shown != null }
        val firstModel = checkNotNull(model)
        val firstSession = checkNotNull(shown)
        val firstTable = retained.boards.value
        val disposeFirst = firstTable.registry.registerMount(firstSession)
        config = backend().copy(id = OtherBackend)
        compose.waitForIdle()
        assertNotSame("backend generations must select different retained page identities", firstModel, model)
        compose.waitUntil(timeoutMillis = NavigationTimeoutMillis) { shown != null }
        val secondModel = checkNotNull(model)
        val secondSession = checkNotNull(shown)
        val secondTable = retained.boards.value
        assertNotSame(firstSession, secondSession)
        assertEquals(0, firstTable.holds(firstSession.canvasId))
        assertEquals(null, firstTable.registry.get(firstSession.canvasId))
        assertEquals(1, secondTable.holds(secondSession.canvasId))
        val disposeSecond = secondTable.registry.registerMount(secondSession)
        config = backend()
        compose.waitForIdle()
        assertNotSame(firstModel, model)
        assertNotSame(secondModel, model)
        compose.waitUntil(timeoutMillis = NavigationTimeoutMillis) { shown != null }
        assertNotSame(firstSession, shown)
        assertNotSame(secondSession, shown)
        assertEquals(0, secondTable.holds(secondSession.canvasId))
        assertEquals(null, secondTable.registry.get(secondSession.canvasId))
        val thirdSession = checkNotNull(shown)
        compose.runOnIdle { viewModels.clear() }
        assertEquals(0, retained.boards.value.holds(thirdSession.canvasId))
        disposeFirst()
        disposeSecond()
    }

    private fun backend() = LettaConfig(Backend, LettaConfig.Mode.SELF_HOSTED, "https://backend.invalid")
}

private const val NavigationTimeoutMillis = 10_000L
private const val FirstPage = "canvas-lifecycle-first"
private const val SecondPage = "canvas-lifecycle-second"
private const val Backend = "canvas-lifecycle-backend"
private const val OtherBackend = "canvas-lifecycle-other-backend"
