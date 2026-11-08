package com.letta.mobile.data.repository

import com.letta.mobile.data.objectverbs.binding.withDevicePins

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.session.BackendSwitchClearResult
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.testutil.createTestPreferencesDataStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import kotlin.time.Duration.Companion.milliseconds
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class SettingsRepositoryTest {

    private lateinit var repository: SettingsRepository

    @BeforeEach
    fun setup() {
        repository = SettingsRepository(
            dataStore = createTestPreferencesDataStore(),
            secureSettingsStore = InMemorySecureSettingsStore().withDevicePins(),
        )
        runBlocking {
            repository.clearAllData()
        }
    }

    @Test
    fun `settings Home and conversation adapters share scoped confirmed pins`() = runTest {
        val storage = InMemorySecureSettingsStore().withDevicePins()
        val settings = SettingsRepository(createTestPreferencesDataStore(), storage)
        val config = LettaConfig(id = "pins", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://pins.invalid")
        settings.saveConfig(config)
        val namespace = com.letta.mobile.data.session.remoteLettaBackendDescriptor(
            config, com.letta.mobile.data.session.ANDROID_REMOTE_LETTA_ID_PREFIX,
        ).backendId.value
        val source = kotlinx.coroutines.flow.MutableStateFlow<String?>(namespace)
        val home = com.letta.mobile.data.home.SettingsStoreHomePinStore(storage, emptyList(), source = source)
        settings.setAgentPinned("alpha", true)
        assertEquals(listOf("agent:alpha"), home.pinnedKeys.value)
        assertEquals(listOf("alpha"), settings.getPinnedAgentOrder().first())
        home.setPinned("agent:alpha", false)
        assertEquals(emptyList<String>(), settings.getPinnedAgentOrder().first())
        settings.setConversationPinned("chat", true)
        val conversations = com.letta.mobile.data.chat.runtime.PinnedConversations(storage, source = source)
        assertEquals(setOf("chat"), conversations.pinned.value)
        conversations.inherit("chat", "fork")
        assertEquals(setOf("chat", "fork"), settings.getPinnedConversationIds().first())
        assertTrue(home.binding.store.journal.value.imports.none { it.key == "desktop.conversations.pinned" && it.complete })
    }

    @Test
    fun `initial state has empty configs`() {
        assertTrue(repository.configs.value.isEmpty())
    }

    @Test
    fun `initial state has null active config`() {
        assertNull(repository.activeConfig.value)
    }

    @Test
    fun `chat font scale emits stored value first`() = runTest {
        repository.setChatFontScale(0.82f)

        assertEquals(0.82f, repository.getChatFontScale().first())
    }

    @Test
    fun `chat font scale defaults to one when absent`() = runTest {
        assertEquals(1f, repository.getChatFontScale().first())
    }

    @Test
    fun `saveConfig adds new config and sets as active`() = runTest {
        val config = LettaConfig(
            id = "c1",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "https://api.letta.com",
            accessToken = "test-token"
        )
        repository.saveConfig(config)

        assertEquals(1, repository.configs.value.size)
        assertEquals("c1", repository.configs.value.first().id)
        assertEquals("c1", repository.activeConfig.value?.id)
    }

    @Test
    fun `saveConfig updates existing config`() = runTest {
        val config = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://old.com")
        repository.saveConfig(config)

        val updated = config.copy(serverUrl = "https://new.com")
        repository.saveConfig(updated)

        assertEquals(1, repository.configs.value.size)
        assertEquals("https://new.com", repository.configs.value.first().serverUrl)
    }

    @Test
    fun `saveConfig persists local on-device model metadata`() = runTest {
        val secureStore = InMemorySecureSettingsStore().withDevicePins()
        val dataStore = createTestPreferencesDataStore()
        val scopedRepository = SettingsRepository(
            dataStore = dataStore,
            secureSettingsStore = secureStore,
        )
        val config = LettaConfig(
            id = "local",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "local-lettacode://device",
            localModelPath = "/sdcard/models/gemma.litertlm",
            localModelHandle = "google/gemma-3n",
            localModelRuntime = "litert-lm",
            localModelAccelerator = "gpu",
            localModelMaxTokens = 32768,
        )

        scopedRepository.saveConfig(config)

        val restoredRepository = SettingsRepository(
            dataStore = dataStore,
            secureSettingsStore = secureStore,
        )
        val restored = restoredRepository.configs.value.single()
        assertEquals("/sdcard/models/gemma.litertlm", restored.localModelPath)
        assertEquals("google/gemma-3n", restored.localModelHandle)
        assertEquals("litert-lm", restored.localModelRuntime)
        assertEquals("gpu", restored.localModelAccelerator)
        assertEquals(32768, restored.localModelMaxTokens)
    }

    @Test
    fun `setActiveConfigId changes active config`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://one.com")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "http://two.com")
        repository.saveConfig(c1)
        repository.saveConfig(c2)

        repository.setActiveConfigId("c1")
        assertEquals("c1", repository.activeConfig.value?.id)
    }

    @Test
    fun `activeConfigChanges emits first real selection after initial null and deduplicates by backend identity`() = runTest {
        val emissions = Channel<LettaConfig>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.activeConfigChanges.collect { emissions.send(it) }
        }

        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://one.com")
        val c1Updated = c1.copy(accessToken = "rotated-token")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "http://two.com")

        repository.saveConfig(c1)
        assertEquals("c1", emissions.receive().id)

        // Token-only rotation keeps the same backend identity -> suppressed.
        repository.saveConfig(c1Updated)
        assertNull(withTimeoutOrNull(100.milliseconds) { emissions.receive() })

        repository.saveConfig(c2)
        assertEquals("c2", emissions.receive().id)
    }

    // letta-mobile-xzoy3: the config-edit mode dropdown reuses the active
    // config's id (ConfigViewModel.saveConfig with asNewEntry=false), so a
    // same-id mode flip must still emit or no consumer refreshes.
    @Test
    fun `activeConfigChanges emits on same-id mode flip`() = runTest {
        val emissions = Channel<LettaConfig>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.activeConfigChanges.collect { emissions.send(it) }
        }

        val remote = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://remote.example.test")
        repository.saveConfig(remote)
        assertEquals("c1", emissions.receive().id)

        repository.saveConfig(remote.copy(mode = LettaConfig.Mode.LOCAL, serverUrl = "local-lettacode://device"))
        assertEquals("c1", emissions.receive().id)

        repository.saveConfig(remote.copy(mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://remote.example.test"))
        assertEquals("c1", emissions.receive().id)
    }

    @Test
    fun `activeConfigChanges emits on same-id server url change`() = runTest {
        val emissions = Channel<LettaConfig>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.activeConfigChanges.collect { emissions.send(it) }
        }

        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "http://old.com")
        repository.saveConfig(c1)
        assertEquals("c1", emissions.receive().id)

        repository.saveConfig(c1.copy(serverUrl = "http://new.com"))
        assertEquals("c1", emissions.receive().id)
    }

    @Test
    fun `active backend change clears backend-scoped caches before active config update`() = runTest {
        lateinit var scopedRepository: CachedSettingsRepository
        val activeIdsAtClear = mutableListOf<String?>()
        scopedRepository = SettingsRepository.forTests(
            dataStore = createTestPreferencesDataStore(),
            secureSettingsStore = InMemorySecureSettingsStore().withDevicePins(),
            clearBackendScopedCaches = {
                activeIdsAtClear += scopedRepository.activeConfig.value?.id
                BackendSwitchClearResult(successes = 1, failures = emptyList())
            },
        )

        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://one.com")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "http://two.com")

        scopedRepository.saveConfig(c1)
        activeIdsAtClear.clear()

        scopedRepository.saveConfig(c2)

        assertEquals(listOf("c1"), activeIdsAtClear)
        assertEquals("c2", scopedRepository.activeConfig.value?.id)

        activeIdsAtClear.clear()
        scopedRepository.setActiveConfigId("c1")

        assertEquals(listOf("c2"), activeIdsAtClear)
        assertEquals("c1", scopedRepository.activeConfig.value?.id)

        activeIdsAtClear.clear()
        scopedRepository.saveConfig(c2.copy(accessToken = "rotated-token"))

        assertEquals(listOf("c1"), activeIdsAtClear)
        assertEquals("c2", scopedRepository.activeConfig.value?.id)

        activeIdsAtClear.clear()
        scopedRepository.saveConfig(c2.copy(accessToken = "rotated-token-2"))

        assertTrue(activeIdsAtClear.isEmpty())
    }

    @Test
    fun `setActiveConfigId with nonexistent id does nothing`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://one.com")
        repository.saveConfig(c1)

        repository.setActiveConfigId("nonexistent")
        assertEquals("c1", repository.activeConfig.value?.id)
    }

    @Test
    fun `deleteConfig removes config`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://one.com")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://two.com")
        repository.saveConfig(c1)
        repository.saveConfig(c2)

        repository.deleteConfig("c1")

        assertEquals(1, repository.configs.value.size)
        assertTrue(repository.configs.value.none { it.id == "c1" })
    }

    @Test
    fun `deleteConfig clears active if deleted was active`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://one.com")
        repository.saveConfig(c1)
        assertEquals("c1", repository.activeConfig.value?.id)

        repository.deleteConfig("c1")

        assertNull(repository.activeConfig.value)
    }

    @Test
    fun `deleteConfig switches active to first remaining`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://one.com")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://two.com")
        repository.saveConfig(c1)
        repository.saveConfig(c2)
        repository.setActiveConfigId("c2")

        repository.deleteConfig("c2")

        assertNotNull(repository.activeConfig.value)
        assertEquals("c1", repository.activeConfig.value?.id)
    }

    @Test
    fun `getTheme returns SYSTEM by default`() = runTest {
        val theme = repository.getTheme().first()
        assertEquals(AppTheme.SYSTEM, theme)
    }

    @Test
    fun `setTheme persists theme`() = runTest {
        repository.setChatBackgroundKey("solid_charcoal")
        repository.setTheme(AppTheme.DARK)
        val theme = repository.getTheme().first()
        assertEquals(AppTheme.DARK, theme)
        assertEquals("default", repository.getChatBackgroundKey().first())
    }

    @Test
    fun `getThemePreset returns DEFAULT by default`() = runTest {
        val preset = repository.getThemePreset().first()
        assertEquals(ThemePreset.DEFAULT, preset)
    }

    @Test
    fun `setThemePreset persists preset`() = runTest {
        repository.setChatBackgroundKey("gradient_night_sky")
        repository.setThemePreset(ThemePreset.AMOLED_BLACK)

        val preset = repository.getThemePreset().first()
        assertEquals(ThemePreset.AMOLED_BLACK, preset)
        assertEquals("default", repository.getChatBackgroundKey().first())
    }

    @Test
    fun `dynamic color defaults off on plain JVM runtime`() = runTest {
        val dynamicColor = repository.getDynamicColor().first()
        assertEquals(false, dynamicColor)
    }

    @Test
    fun `setDynamicColor persists selection`() = runTest {
        repository.setDynamicColor(false)

        val dynamicColor = repository.getDynamicColor().first()
        assertEquals(false, dynamicColor)
    }

    // Retain the name regression coverage for owner-confirmed writes, with source fencing.
    @Test
    fun `unknown source legacy selection stays on disk but is not a startup selection`() {
        val secureStore = InMemorySecureSettingsStore().withDevicePins()
        val legacy = """{"agentId":"agent-1","agentName":"Agent","conversationId":"conv-1"}"""
        secureStore.putString(LastChatSelectionStorage.KEY, legacy)
        val restored = SettingsRepository(createTestPreferencesDataStore(), secureStore)

        assertNull(restored.lastChatSelection.value)
        assertEquals(legacy, secureStore.getString(LastChatSelectionStorage.KEY))
    }

    @Test
    fun `legacy screen writer cannot overwrite the unresolved triple`() {
        val secureStore = InMemorySecureSettingsStore().withDevicePins()
        val legacy = """{"agentId":"agent-1","agentName":"Agent","conversationId":"conv-1"}"""
        secureStore.putString(LastChatSelectionStorage.KEY, legacy)
        val settings = SettingsRepository(createTestPreferencesDataStore(), secureStore)

        settings.setLastChatSelection("agent-2", "Other", "conv-2")

        assertEquals(legacy, secureStore.getString(LastChatSelectionStorage.KEY))
    }

    @Test
    fun `settled selection blank agentName preserves stored name for same source and agent`() {
        repository.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), "PM-letta-mobile")
        assertEquals("PM-letta-mobile", repository.lastChatSeed("source-1")?.agentName)

        repository.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), null)
        assertEquals("PM-letta-mobile", repository.lastChatSeed("source-1")?.agentName)

        repository.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), "   ")
        assertEquals("PM-letta-mobile", repository.lastChatSeed("source-1")?.agentName)
    }

    @Test
    fun `settled selection blank agentName does not leak name across agents or sources`() {
        repository.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), "PM-letta-mobile")
        repository.setSettledChatSelection("source-1", AgentId("agent-2"), ConversationId("conv-2"), null)
        assertEquals("agent-2", repository.lastChatSeed("source-1")?.agentId)
        assertNull(repository.lastChatSeed("source-1")?.agentName)

        repository.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), "PM-letta-mobile")
        repository.setSettledChatSelection("source-2", AgentId("agent-1"), ConversationId("conv-2"), null)
        assertNull(repository.lastChatSeed("source-2")?.agentName)
        assertNull(repository.lastChatSeed("source-1"))
    }

    @Test
    fun `settled seed survives restart and remains source scoped`() {
        val secureStore = InMemorySecureSettingsStore().withDevicePins()
        val dataStore = createTestPreferencesDataStore()
        val initialRepo = SettingsRepository(dataStore = dataStore, secureSettingsStore = secureStore)
        initialRepo.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), "PM-letta-mobile")
        initialRepo.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), null)

        val restoredRepo = SettingsRepository(dataStore = dataStore, secureSettingsStore = secureStore)
        assertEquals("PM-letta-mobile", restoredRepo.lastChatSeed("source-1")?.agentName)
        assertEquals("conv-1", restoredRepo.lastChatSeed("source-1")?.conversationId)
        assertNull(restoredRepo.lastChatSeed("source-2"))
    }

    @Test
    fun `settled selection still stores a newly resolved name`() {
        repository.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), null)
        assertNull(repository.lastChatSeed("source-1")?.agentName)

        repository.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), "PM-letta-mobile")
        assertEquals("PM-letta-mobile", repository.lastChatSeed("source-1")?.agentName)
    }

    @Test
    fun `settled seed preserves legacy record and rejects a blank source`() {
        val secureStore = InMemorySecureSettingsStore().withDevicePins()
        val legacy = """{"agentId":"legacy","conversationId":"conv-legacy"}"""
        secureStore.putString(LastChatSelectionStorage.KEY, legacy)
        val settings = SettingsRepository(createTestPreferencesDataStore(), secureStore)
        settings.setSettledChatSelection(" ", AgentId("agent-1"), ConversationId("conv-1"), "Agent")
        assertNull(settings.lastChatSeed(" "))
        settings.setSettledChatSelection("source-1", AgentId("agent-1"), ConversationId("conv-1"), "Agent")
        assertEquals("agent-1", settings.lastChatSeed("source-1")?.agentId)
        assertEquals(legacy, secureStore.getString(LastChatSelectionStorage.KEY))
    }

}
