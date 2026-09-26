package com.letta.mobile

import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
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

    private fun newDependencies() = MainActivityDependencies(
        lazySettingsRepository = settingsRepository,
        crashReporter = mockk<CrashReporter>(),
        lazyAgentRepository = agentRepository,
        lazySecureSettingsStore = secureSettingsStore,
        lazyConversationRunRegistry = conversationRunRegistry,
    )

    @Test
    fun constructionDoesNotResolveTheDeferredGraph() {
        newDependencies()

        assertEquals(0, settingsRepository.resolutions)
        assertEquals(0, agentRepository.resolutions)
        assertEquals(0, secureSettingsStore.resolutions)
        assertEquals(0, conversationRunRegistry.resolutions)
    }

    @Test
    fun accessorsResolveTheInjectedSingletons() {
        val dependencies = newDependencies()

        assertSame(settingsRepository.value, dependencies.settingsRepository)
        assertSame(agentRepository.value, dependencies.agentRepository)
        assertSame(secureSettingsStore.value, dependencies.secureSettingsStore)
        assertSame(conversationRunRegistry.value, dependencies.conversationRunRegistry)
        assertEquals(1, settingsRepository.resolutions)
    }

    private class CountingLazy<T>(val value: T) : Lazy<T> {
        var resolutions = 0
            private set

        override fun get(): T {
            resolutions += 1
            return value
        }
    }
}
