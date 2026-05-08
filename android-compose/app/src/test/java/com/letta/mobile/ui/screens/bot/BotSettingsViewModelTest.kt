package com.letta.mobile.ui.screens.bot

import android.app.ActivityManager
import android.app.Application
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotConfigStore
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class BotSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val application: Application = mockk(relaxed = true)
    private val activityManager: ActivityManager = mockk(relaxed = true)
    private val configStore: BotConfigStore = mockk(relaxed = true)
    private val configsFlow = MutableStateFlow<List<BotConfig>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { application.applicationContext } returns application
        every { application.getSystemService(any()) } returns activityManager
        every { configStore.configs } returns configsFlow
    }

    @Test
    fun configsReflectStoreState() = runTest(testDispatcher) {
        val testConfig = BotConfig(
            id = "cfg-1",
            displayName = "Test Bot",
        )
        configsFlow.value = listOf(testConfig)

        val vm = BotSettingsViewModel(application, configStore)

        val configs = vm.configs.first()
        assert(configs.size == 1) { "Expected 1 config, got ${configs.size}" }
        assert(configs.first().displayName == "Test Bot") {
            "Expected 'Test Bot', got ${configs.first().displayName}"
        }
    }

    @Test
    fun deleteConfigCallsStore() = runTest(testDispatcher) {
        coJustRun { configStore.deleteConfig(any()) }

        val vm = BotSettingsViewModel(application, configStore)
        vm.deleteConfig("cfg-to-delete")

        coVerify { configStore.deleteConfig("cfg-to-delete") }
    }

    @Test
    fun toggleConfigEnabledFlipsEnabled() = runTest(testDispatcher) {
        coJustRun { configStore.saveConfig(any()) }
        val enabledConfig = BotConfig(id = "cfg-1", displayName = "Bot", enabled = true)

        val vm = BotSettingsViewModel(application, configStore)
        vm.toggleConfigEnabled(enabledConfig)

        coVerify { configStore.saveConfig(match { it.id == "cfg-1" && !it.enabled }) }
    }
}
