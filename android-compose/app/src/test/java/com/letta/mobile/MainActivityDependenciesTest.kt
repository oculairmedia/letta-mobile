package com.letta.mobile

import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.runtime.AgentDefaultsRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import dagger.Lazy
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * letta-mobile-wyo3p: injecting [MainActivity] must not build the settings /
 * EncryptedSharedPreferences graph on the main thread; it is resolved on first use.
 */
class MainActivityDependenciesTest {
    private val settingsRepository = CountingLazy(mockk<ISettingsRepository>())
    private val agentRepository = CountingLazy(mockk<IAgentRepository>())
    private val secureSettingsStore = CountingLazy(mockk<SecureSettingsStore>())
    private val conversationRunRegistry = CountingLazy(mockk<ConversationRunRegistry>())
    private val attentionPrefs = CountingLazy(mockk<AttentionPrefsRepository>())
    private val agentDefaults = CountingLazy(mockk<AgentDefaultsRepository>())
    private val boardTable = mockk<CanvasBoardSessions>()
    private val canvasBoards = CountingLazy(mockk<com.letta.mobile.ui.screens.canvas.AndroidCanvasBoards> {
        io.mockk.every { boards } returns kotlinx.coroutines.flow.MutableStateFlow(boardTable)
    })
    private val canvasViewers = CountingLazy(com.letta.mobile.ui.canvas.plugin.CanvasDocumentViewerRegistry())
    private val subagentRepository = CountingLazy(mockk<ISubagentRepository>())

    private fun newDependencies(
        boardProvider: Lazy<com.letta.mobile.ui.screens.canvas.AndroidCanvasBoards> = canvasBoards,
    ) = MainActivityDependencies(
        lazySettingsRepository = settingsRepository,
        crashReporter = mockk<CrashReporter>(),
        lazyAgentRepository = agentRepository,
        lazySecureSettingsStore = secureSettingsStore,
        lazyConversationRunRegistry = conversationRunRegistry,
        lazyAttentionPrefs = attentionPrefs,
        lazyAgentDefaults = agentDefaults,
        lazyCanvasBoards = boardProvider,
        lazyCanvasViewers = canvasViewers,
        lazySubagentRepository = subagentRepository,
    )

    @Test
    fun constructionDoesNotResolveTheDeferredGraph() {
        newDependencies()

        assertEquals(0, settingsRepository.resolutions)
        assertEquals(0, agentRepository.resolutions)
        assertEquals(0, secureSettingsStore.resolutions)
        assertEquals(0, conversationRunRegistry.resolutions)
        assertEquals(0, attentionPrefs.resolutions)
        assertEquals(0, agentDefaults.resolutions)
        assertEquals(0, canvasBoards.resolutions)
        assertEquals(0, canvasViewers.resolutions)
        assertEquals(0, subagentRepository.resolutions)
    }

    @Test
    fun accessorsResolveTheInjectedSingletons() {
        val dependencies = newDependencies()

        assertSame(settingsRepository.value, dependencies.settingsRepository)
        assertSame(agentRepository.value, dependencies.agentRepository)
        assertSame(secureSettingsStore.value, dependencies.secureSettingsStore)
        assertSame(conversationRunRegistry.value, dependencies.conversationRunRegistry)
        assertSame(attentionPrefs.value, dependencies.attentionPrefs)
        assertSame(agentDefaults.value, dependencies.agentDefaults)
        assertSame(boardTable, dependencies.canvasOwner.boards.value)
        assertSame(canvasBoards.value, dependencies.canvasOwner)
        assertSame(canvasViewers.value, dependencies.canvasViewers)
        assertSame(subagentRepository.value, dependencies.subagentRepository)
        assertEquals(1, settingsRepository.resolutions)
    }

    @Test
    fun boardOwnersAreFreshForEachWindowOrAccountAndAdoptTheirOwnMounts() = kotlinx.coroutines.runBlocking {
        val store = com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore()
        val opLog = com.letta.mobile.data.canvas.InMemoryCanvasOpLog()
        val transport = mockk<com.letta.mobile.data.canvas.CanvasSyncTransport>()
        val factory = javax.inject.Provider {
            com.letta.mobile.di.AppModule.provideCanvasBoardSessions(store, opLog, transport)
        }
        fun window(): MainActivityDependencies {
            val owner = com.letta.mobile.ui.screens.canvas.AndroidCanvasBoards(factory, mockk(relaxed = true))
            return newDependencies(Lazy { owner })
        }
        val dependencies = window()
        val first = dependencies.canvasOwner.boards.value
        val second = window().canvasOwner.boards.value
        assertSame(first, dependencies.canvasOwner.boards.value)
        org.junit.Assert.assertNotSame(first, second)
        org.junit.Assert.assertNotSame(first.registry, second.registry)
        val page = com.letta.mobile.data.canvas.CanvasSession.create(store)
        val dispose = first.registry.registerMount(page)
        assertSame(page, first.acquire(page.canvasId))
        assertEquals(null, second.registry.get(page.canvasId))
        first.release(page)
        dispose()
        assertEquals(0, first.holds(page.canvasId))
        assertEquals(null, first.registry.get(page.canvasId))
    }

    private class CountingLazy<T>(val value: T) : Lazy<T>, javax.inject.Provider<T> {
        var resolutions = 0
            private set

        override fun get(): T {
            resolutions += 1
            return value
        }
    }
}
