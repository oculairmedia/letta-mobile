package com.letta.mobile.ui.screens.config

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.api.CloudConnectionValidationResult
import com.letta.mobile.data.api.CloudConnectionValidator
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class ConfigViewModelTest {

    private lateinit var fakeRepository: FakeSettingsRepository
    private lateinit var fakeValidator: FakeCloudConnectionValidator
    private lateinit var viewModel: ConfigViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSettingsRepository()
        fakeValidator = FakeCloudConnectionValidator()
        // letta-mobile-cdlk: ConfigViewModel now reads ConfigRoute(createNew)
        // from SavedStateHandle. Empty handle is fine for the existing edit-
        // active-config test cases (createNew defaults to false when the
        // route arg is absent).
        viewModel = ConfigViewModel(SavedStateHandle(), fakeRepository, fakeValidator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadConfig_withNullConfig_setsUiStateSuccess_withDefaultConfigUiState() = runTest {
        fakeRepository.activeConfigState.value = null

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
        fakeRepository.activeConfigState.value = config

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
        fakeRepository.activeConfigState.value = config

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
        fakeRepository.activeConfigState.value = config

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
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.CLOUD)
        viewModel.updateServerUrl("https://api.letta.com")
        viewModel.updateApiToken("new-token")

        var onSuccessCalled = false
        viewModel.saveConfig(onSuccess = {
            onSuccessCalled = true
        })

        val savedConfig = fakeRepository.activeConfig.value
        assertEquals(LettaConfig.Mode.CLOUD, savedConfig?.mode)
        assertEquals("https://api.letta.com", savedConfig?.serverUrl)
        assertEquals("new-token", savedConfig?.accessToken)
        assertTrue(onSuccessCalled)
    }

    @Test
    fun saveConfig_buildsLettaConfigFromFormState_withSelfHostedMode() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.SELF_HOSTED)
        viewModel.updateServerUrl("http://192.168.1.100:8080")
        viewModel.updateApiToken("local-token")

        var onSuccessCalled = false
        viewModel.saveConfig(onSuccess = {
            onSuccessCalled = true
        })

        val savedConfig = fakeRepository.activeConfig.value
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
        fakeRepository.activeConfigState.value = existingConfig
        viewModel.loadConfig()

        viewModel.updateServerUrl("https://new.letta.ai")

        viewModel.saveConfig(onSuccess = {})

        val savedConfig = fakeRepository.activeConfig.value
        assertEquals("existing-id-123", savedConfig?.id)
        assertEquals(ConfigViewModel.DEFAULT_CLOUD_URL, savedConfig?.serverUrl)
    }

    @Test
    fun saveConfig_withBlankCloudToken_reportsErrorAndDoesNotSave() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.CLOUD)
        viewModel.updateServerUrl("https://api.letta.com")
        viewModel.updateApiToken("")

        var errorMessage: String? = null
        viewModel.saveConfig(onSuccess = {}, onError = { errorMessage = it })

        assertEquals(null, fakeRepository.activeConfig.value)
        assertEquals(
            "Letta Cloud API key is required. Paste a key from app.letta.com before saving.",
            errorMessage,
        )
    }

    @Test
    fun saveConfig_withRejectedCloudToken_reportsErrorAndDoesNotSave() = runTest {
        fakeRepository.activeConfigState.value = null
        fakeValidator.result = CloudConnectionValidationResult.Failed("bad key")
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.CLOUD)
        viewModel.updateApiToken("bad-token")

        var onSuccessCalled = false
        var errorMessage: String? = null
        viewModel.saveConfig(
            onSuccess = { onSuccessCalled = true },
            onError = { errorMessage = it },
        )

        assertEquals(null, fakeRepository.activeConfig.value)
        assertEquals("bad key", errorMessage)
        assertEquals(false, onSuccessCalled)
    }

    @Test
    fun updateMode_updatesStateCorrectly() = runTest {
        fakeRepository.activeConfigState.value = null
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
    fun updateMode_roundTripsCloudAndSelfHostedUrlsCorrectly() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.CLOUD)
        var successState = (viewModel.uiState.value as UiState.Success).data
        assertEquals(ConfigViewModel.DEFAULT_CLOUD_URL, successState.serverUrl)

        viewModel.updateMode(ServerMode.SELF_HOSTED)
        successState = (viewModel.uiState.value as UiState.Success).data
        assertEquals("", successState.serverUrl)

        viewModel.updateServerUrl("http://localhost:8080")
        viewModel.updateMode(ServerMode.CLOUD)
        successState = (viewModel.uiState.value as UiState.Success).data
        assertEquals(ConfigViewModel.DEFAULT_CLOUD_URL, successState.serverUrl)

        viewModel.updateMode(ServerMode.SELF_HOSTED)
        successState = (viewModel.uiState.value as UiState.Success).data
        assertEquals("", successState.serverUrl)
    }

    @Test
    fun updateServerUrl_updatesStateCorrectly() = runTest {
        fakeRepository.activeConfigState.value = null
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
        fakeRepository.activeConfigState.value = null
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
        fakeRepository.activeConfigState.value = null
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
        fakeRepository.activeConfigState.value = null
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
        fakeRepository.activeConfigState.value = null
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
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()
        fakeRepository.setChatBackgroundKey("solid_charcoal")

        viewModel.updateThemePreset(ThemePreset.AMOLED_BLACK)
        viewModel.updateDynamicColor(false)
        viewModel.updateServerUrl("https://api.letta.com")
        viewModel.updateApiToken("new-token")

        viewModel.saveConfig(onSuccess = {})

        assertEquals(ThemePreset.AMOLED_BLACK, fakeRepository.getThemePreset().first())
        assertEquals(false, fakeRepository.getDynamicColor().first())
        assertEquals("default", fakeRepository.getChatBackgroundKey().first())
    }

    @Test
    fun saveConfig_trimsSelfHostedUrlAndToken() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.SELF_HOSTED)
        viewModel.updateServerUrl("  https://self-hosted.letta.dev  ")
        viewModel.updateApiToken("  token-with-spaces  ")

        viewModel.saveConfig(onSuccess = {})

        val savedConfig = fakeRepository.activeConfig.value
        assertEquals("https://self-hosted.letta.dev", savedConfig?.serverUrl)
        assertEquals("token-with-spaces", savedConfig?.accessToken)
    }

    private class FakeCloudConnectionValidator(
        var result: CloudConnectionValidationResult = CloudConnectionValidationResult.Success,
    ) : CloudConnectionValidator() {
        override suspend fun validate(
            baseUrl: String,
            apiToken: String,
        ): CloudConnectionValidationResult = result
    }
}
