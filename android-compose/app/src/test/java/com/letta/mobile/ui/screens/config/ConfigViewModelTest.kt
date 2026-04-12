package com.letta.mobile.ui.screens.config

import android.content.Context
import app.cash.turbine.test
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.util.EncryptedPrefsHelper
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {

    private lateinit var fakeRepository: FakeSettingsRepository
    private lateinit var viewModel: ConfigViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val sharedPreferences = appContext.getSharedPreferences("config-view-model-test", Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().commit()
        mockkObject(EncryptedPrefsHelper)
        every { EncryptedPrefsHelper.getEncryptedPrefs(any()) } returns sharedPreferences
        fakeRepository = FakeSettingsRepository(appContext)
        viewModel = ConfigViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        unmockkObject(EncryptedPrefsHelper)
        Dispatchers.resetMain()
    }

    @Test
    fun loadConfig_withNullConfig_setsUiStateSuccess_withDefaultConfigUiState() = runTest {
        fakeRepository.setActiveConfig(null)

        viewModel.loadConfig()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(ServerMode.CLOUD, successState.mode)
            assertEquals("", successState.serverUrl)
            assertEquals("", successState.apiToken)
            assertEquals(AppTheme.SYSTEM, successState.theme)
            assertEquals(ThemePreset.DEFAULT, successState.themePreset)
            assertEquals(true, successState.dynamicColor)
        }
    }

    @Test
    fun loadConfig_withExistingCloudConfig_mapsModeUrlTokenCorrectly() = runTest {
        val config = LettaConfig(
            id = "config-1",
            mode = LettaConfig.Mode.CLOUD,
            serverUrl = "https://cloud.letta.ai",
            accessToken = "cloud-token-123"
        )
        fakeRepository.setActiveConfig(config)

        viewModel.loadConfig()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(ServerMode.CLOUD, successState.mode)
            assertEquals("https://cloud.letta.ai", successState.serverUrl)
            assertEquals("cloud-token-123", successState.apiToken)
        }
    }

    @Test
    fun loadConfig_withExistingSelfHostedConfig_mapsModeUrlTokenCorrectly() = runTest {
        val config = LettaConfig(
            id = "config-2",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8080",
            accessToken = "self-hosted-token-456"
        )
        fakeRepository.setActiveConfig(config)

        viewModel.loadConfig()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(ServerMode.SELF_HOSTED, successState.mode)
            assertEquals("http://localhost:8080", successState.serverUrl)
            assertEquals("self-hosted-token-456", successState.apiToken)
        }
    }

    @Test
    fun loadConfig_withConfigWithoutToken_mapsToEmptyString() = runTest {
        val config = LettaConfig(
            id = "config-3",
            mode = LettaConfig.Mode.CLOUD,
            serverUrl = "https://cloud.letta.ai",
            accessToken = null
        )
        fakeRepository.setActiveConfig(config)

        viewModel.loadConfig()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals("", successState.apiToken)
        }
    }

    @Test
    fun saveConfig_buildsLettaConfigFromFormState_withCloudMode() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.CLOUD)
        viewModel.updateServerUrl("https://api.letta.com")
        viewModel.updateApiToken("new-token")

        var onSuccessCalled = false
        viewModel.saveConfig(onSuccess = {
            onSuccessCalled = true
        })

        val savedConfig = fakeRepository.getSavedConfig()
        assertEquals(LettaConfig.Mode.CLOUD, savedConfig?.mode)
        assertEquals("https://api.letta.com", savedConfig?.serverUrl)
        assertEquals("new-token", savedConfig?.accessToken)
        assertTrue(onSuccessCalled)
    }

    @Test
    fun saveConfig_buildsLettaConfigFromFormState_withSelfHostedMode() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.SELF_HOSTED)
        viewModel.updateServerUrl("http://192.168.1.100:8080")
        viewModel.updateApiToken("local-token")

        var onSuccessCalled = false
        viewModel.saveConfig(onSuccess = {
            onSuccessCalled = true
        })

        val savedConfig = fakeRepository.getSavedConfig()
        assertEquals(LettaConfig.Mode.SELF_HOSTED, savedConfig?.mode)
        assertEquals("http://192.168.1.100:8080", savedConfig?.serverUrl)
        assertEquals("local-token", savedConfig?.accessToken)
        assertTrue(onSuccessCalled)
    }

    @Test
    fun saveConfig_preservesExistingConfigId() = runTest {
        val existingConfig = LettaConfig(
            id = "existing-id-123",
            mode = LettaConfig.Mode.CLOUD,
            serverUrl = "https://old.letta.ai",
            accessToken = "old-token"
        )
        fakeRepository.setActiveConfig(existingConfig)
        viewModel.loadConfig()

        viewModel.updateServerUrl("https://new.letta.ai")

        viewModel.saveConfig(onSuccess = {})

        val savedConfig = fakeRepository.getSavedConfig()
        assertEquals("existing-id-123", savedConfig?.id)
        assertEquals("https://new.letta.ai", savedConfig?.serverUrl)
    }

    @Test
    fun saveConfig_withBlankToken_savesAsNull() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.CLOUD)
        viewModel.updateServerUrl("https://api.letta.com")
        viewModel.updateApiToken("")

        viewModel.saveConfig(onSuccess = {})

        val savedConfig = fakeRepository.getSavedConfig()
        assertEquals(null, savedConfig?.accessToken)
    }

    @Test
    fun updateMode_updatesStateCorrectly() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.SELF_HOSTED)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(ServerMode.SELF_HOSTED, successState.mode)
        }
    }

    @Test
    fun updateServerUrl_updatesStateCorrectly() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateServerUrl("http://test.server")

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals("http://test.server", successState.serverUrl)
        }
    }

    @Test
    fun updateApiToken_updatesStateCorrectly() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateApiToken("test-token")

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals("test-token", successState.apiToken)
        }
    }

    @Test
    fun updateTheme_updatesStateCorrectly() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateTheme(AppTheme.DARK)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(AppTheme.DARK, successState.theme)
        }
    }

    @Test
    fun updateThemePreset_updatesStateCorrectly() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateThemePreset(ThemePreset.SAKURA)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(ThemePreset.SAKURA, successState.themePreset)
            assertEquals(false, successState.dynamicColor)
        }
    }

    @Test
    fun updateDynamicColor_updatesStateCorrectly() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()

        viewModel.updateDynamicColor(false)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(false, successState.dynamicColor)
        }
    }

    @Test
    fun saveConfig_persistsThemePresetAndDynamicColor() = runTest {
        fakeRepository.setActiveConfig(null)
        viewModel.loadConfig()
        fakeRepository.setChatBackgroundKey("solid_charcoal")

        viewModel.updateThemePreset(ThemePreset.AMOLED_BLACK)
        viewModel.updateDynamicColor(false)
        viewModel.updateServerUrl("https://api.letta.com")

        viewModel.saveConfig(onSuccess = {})

        assertEquals(ThemePreset.AMOLED_BLACK, fakeRepository.getThemePreset().first())
        assertEquals(false, fakeRepository.getDynamicColor().first())
        assertEquals("default", fakeRepository.getChatBackgroundKey().first())
    }

    private class FakeSettingsRepository(context: Context) : SettingsRepository(context) {
        private val _activeConfig = MutableStateFlow<LettaConfig?>(null)
        override val activeConfig: StateFlow<LettaConfig?> = _activeConfig.asStateFlow()

        private val _configs = MutableStateFlow<List<LettaConfig>>(emptyList())
        override val configs: StateFlow<List<LettaConfig>> = _configs.asStateFlow()

        private val themeFlow = MutableStateFlow(AppTheme.SYSTEM)
        private val themePresetFlow = MutableStateFlow(ThemePreset.DEFAULT)
        private val dynamicColorFlow = MutableStateFlow(true)
        private val chatBackgroundFlow = MutableStateFlow("default")

        private var savedConfig: LettaConfig? = null

        fun setActiveConfig(config: LettaConfig?) {
            _activeConfig.value = config
        }

        fun getSavedConfig(): LettaConfig? = savedConfig

        override suspend fun saveConfig(config: LettaConfig) {
            savedConfig = config
            _activeConfig.value = config
            val updatedConfigs = _configs.value.toMutableList()
            val existingIndex = updatedConfigs.indexOfFirst { it.id == config.id }
            if (existingIndex >= 0) {
                updatedConfigs[existingIndex] = config
            } else {
                updatedConfigs.add(config)
            }
            _configs.value = updatedConfigs
        }

        override fun getTheme() = themeFlow

        override suspend fun setTheme(theme: AppTheme) {
            themeFlow.value = theme
            chatBackgroundFlow.value = "default"
        }

        override fun getThemePreset() = themePresetFlow

        override suspend fun setThemePreset(themePreset: ThemePreset) {
            themePresetFlow.value = themePreset
            chatBackgroundFlow.value = "default"
        }

        override fun getDynamicColor() = dynamicColorFlow

        override suspend fun setDynamicColor(enabled: Boolean) {
            dynamicColorFlow.value = enabled
        }

        override fun getChatBackgroundKey() = chatBackgroundFlow

        override suspend fun setChatBackgroundKey(key: String) {
            chatBackgroundFlow.value = key
        }
    }
}
