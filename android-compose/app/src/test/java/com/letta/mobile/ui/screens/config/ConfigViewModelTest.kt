package com.letta.mobile.ui.screens.config

import android.net.Uri
import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.api.CloudConnectionValidationResult
import com.letta.mobile.data.api.CloudConnectionValidator
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.ImportedOnDeviceModel
import com.letta.mobile.runtime.local.OnDeviceModelImporter
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogEntry
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDefaultConfig
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private lateinit var fakeModelImporter: FakeOnDeviceModelImporter
    private lateinit var fakeEmbeddedModelRepository: FakeEmbeddedModelRepository
    private lateinit var viewModel: ConfigViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSettingsRepository()
        fakeValidator = FakeCloudConnectionValidator()
        fakeModelImporter = FakeOnDeviceModelImporter()
        fakeEmbeddedModelRepository = FakeEmbeddedModelRepository()
        // letta-mobile-cdlk: ConfigViewModel now reads ConfigRoute(createNew)
        // from SavedStateHandle. Empty handle is fine for the existing edit-
        // active-config test cases (createNew defaults to false when the
        // route arg is absent).
        viewModel = ConfigViewModel(
            SavedStateHandle(),
            fakeRepository,
            fakeValidator,
            FakeEmbeddedLettaCodeRuntimeStatusProvider(),
            fakeModelImporter,
            fakeEmbeddedModelRepository,
        )
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
    fun loadConfig_withExistingLocalConfig_mapsModeUrlAndClearsToken() = runTest {
        val config = LettaConfig(
            id = "config-local",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = ConfigViewModel.LOCAL_RUNTIME_URL,
            accessToken = null,
        )
        fakeRepository.activeConfigState.value = config

        viewModel.loadConfig()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(ServerMode.LOCAL, successState.mode)
            assertEquals(ConfigViewModel.LOCAL_RUNTIME_URL, successState.serverUrl)
            assertEquals("", successState.apiToken)
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
    fun saveConfig_buildsLettaConfigFromFormState_withLocalMode() = runTest {
        fakeRepository.activeConfigState.value = null
        fakeValidator.result = CloudConnectionValidationResult.Failed("local should not validate cloud")
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.LOCAL)
        viewModel.updateApiToken("ignored-local-token")

        var onSuccessCalled = false
        var errorMessage: String? = null
        viewModel.saveConfig(
            onSuccess = { onSuccessCalled = true },
            onError = { errorMessage = it },
        )

        val savedConfig = fakeRepository.activeConfig.value
        assertEquals(LettaConfig.Mode.LOCAL, savedConfig?.mode)
        assertEquals(ConfigViewModel.LOCAL_RUNTIME_URL, savedConfig?.serverUrl)
        assertEquals(null, savedConfig?.accessToken)
        assertEquals(0, fakeValidator.calls)
        assertEquals(null, errorMessage)
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
    fun saveConfig_withBlankCloudToken_keepsImmediatePreferenceChanges() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateTheme(AppTheme.DARK)
        viewModel.updateThemePreset(ThemePreset.SAKURA)
        viewModel.updateDynamicColor(false)
        viewModel.updateEnableProjects(false)
        viewModel.updateMode(ServerMode.CLOUD)
        viewModel.updateApiToken("")

        var errorMessage: String? = null
        viewModel.saveConfig(onSuccess = {}, onError = { errorMessage = it })

        assertEquals(null, fakeRepository.activeConfig.value)
        assertEquals(
            "Letta Cloud API key is required. Paste a key from app.letta.com before saving.",
            errorMessage,
        )
        assertEquals(AppTheme.DARK, fakeRepository.getTheme().first())
        assertEquals(ThemePreset.SAKURA, fakeRepository.getThemePreset().first())
        assertEquals(false, fakeRepository.getDynamicColor().first())
        assertEquals(false, fakeRepository.getEnableProjects().first())
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
    fun saveConfig_withRejectedCloudToken_keepsImmediateThemeChanges() = runTest {
        fakeRepository.activeConfigState.value = null
        fakeValidator.result = CloudConnectionValidationResult.Failed("bad key")
        viewModel.loadConfig()

        viewModel.updateTheme(AppTheme.DARK)
        viewModel.updateThemePreset(ThemePreset.SAKURA)
        viewModel.updateDynamicColor(false)
        viewModel.updateMode(ServerMode.CLOUD)
        viewModel.updateApiToken("bad-token")

        viewModel.saveConfig(onSuccess = {}, onError = {})

        assertEquals(null, fakeRepository.activeConfig.value)
        assertEquals(AppTheme.DARK, fakeRepository.getTheme().first())
        assertEquals(ThemePreset.SAKURA, fakeRepository.getThemePreset().first())
        assertEquals(false, fakeRepository.getDynamicColor().first())
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

        viewModel.updateMode(ServerMode.LOCAL)
        successState = (viewModel.uiState.value as UiState.Success).data
        assertEquals(ConfigViewModel.LOCAL_RUNTIME_URL, successState.serverUrl)

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
        assertEquals(AppTheme.DARK, fakeRepository.getTheme().first())
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
        assertEquals(ThemePreset.SAKURA, fakeRepository.getThemePreset().first())
        assertEquals(false, fakeRepository.getDynamicColor().first())
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
        assertEquals(false, fakeRepository.getDynamicColor().first())
    }

    @Test
    fun updateEnableProjects_updatesStateCorrectly() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateEnableProjects(false)

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals(false, successState.enableProjects)
        }
        assertEquals(false, fakeRepository.getEnableProjects().first())
    }

    @Test
    fun updateThemePreset_persistsThemePresetAndDynamicColor() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()
        fakeRepository.setChatBackgroundKey("solid_charcoal")

        viewModel.updateThemePreset(ThemePreset.AMOLED_BLACK)
        viewModel.updateDynamicColor(false)

        assertEquals(ThemePreset.AMOLED_BLACK, fakeRepository.getThemePreset().first())
        assertEquals(false, fakeRepository.getDynamicColor().first())
        assertEquals("default", fakeRepository.getChatBackgroundKey().first())
    }

    @Test
    fun loadConfig_withExistingLocalConfig_mapsOnDeviceModelFields() = runTest {
        val config = LettaConfig(
            id = "config-local",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = ConfigViewModel.LOCAL_RUNTIME_URL,
            localModelPath = "/sdcard/models/gemma.litertlm",
            localModelHandle = "google/gemma-3n",
            localModelRuntime = "litert-lm",
            localModelAccelerator = "cpu",
            localModelMaxTokens = 8192,
        )
        fakeRepository.activeConfigState.value = config

        viewModel.loadConfig()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            val successState = (state as UiState.Success).data
            assertEquals("/sdcard/models/gemma.litertlm", successState.localModelPath)
            assertEquals("google/gemma-3n", successState.localModelHandle)
            assertEquals("cpu", successState.localModelAccelerator)
            assertEquals("8192", successState.localModelMaxTokens)
        }
    }

    @Test
    fun saveConfig_buildsLettaConfigWithOnDeviceModelFields_withLocalMode() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.LOCAL)
        viewModel.updateLocalModelPath("  /sdcard/models/gemma.litertlm  ")
        viewModel.updateLocalModelHandle("  google/gemma-3n  ")
        viewModel.updateLocalModelAccelerator("cpu")
        viewModel.updateLocalModelMaxTokens("8192")

        viewModel.saveConfig(onSuccess = {})

        val savedConfig = fakeRepository.activeConfig.value
        assertEquals(LettaConfig.Mode.LOCAL, savedConfig?.mode)
        assertEquals("/sdcard/models/gemma.litertlm", savedConfig?.localModelPath)
        assertEquals("google/gemma-3n", savedConfig?.localModelHandle)
        assertEquals(ConfigViewModel.DEFAULT_LOCAL_MODEL_RUNTIME, savedConfig?.localModelRuntime)
        assertEquals("cpu", savedConfig?.localModelAccelerator)
        assertEquals(8192, savedConfig?.localModelMaxTokens)
    }

    @Test
    fun importLocalModel_updatesLocalModelPathAndHandle() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()
        fakeModelImporter.nextModel = ImportedOnDeviceModel(
            displayName = "Gemma 3n.litertlm",
            fileName = "Gemma_3n.litertlm",
            path = "/data/user/0/com.letta.mobile/files/embedded-lettacode/models/Gemma_3n.litertlm",
            handle = "local/gemma-3n",
        )

        var importedFileName: String? = null
        viewModel.importLocalModel(
            uri = mockk(relaxed = true),
            onSuccess = { importedFileName = it },
        )

        val state = viewModel.uiState.first()
        assertTrue(state is UiState.Success)
        val successState = (state as UiState.Success).data
        assertEquals(ServerMode.LOCAL, successState.mode)
        assertEquals(ConfigViewModel.LOCAL_RUNTIME_URL, successState.serverUrl)
        assertEquals(
            "/data/user/0/com.letta.mobile/files/embedded-lettacode/models/Gemma_3n.litertlm",
            successState.localModelPath,
        )
        assertEquals("local/gemma-3n", successState.localModelHandle)
        assertEquals(false, successState.isImportingLocalModel)
        assertEquals("Gemma_3n.litertlm", importedFileName)
    }

    @Test
    fun saveConfig_withInvalidLocalMaxTokens_reportsErrorAndDoesNotSave() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.LOCAL)
        viewModel.updateLocalModelMaxTokens("not-a-number")

        var errorMessage: String? = null
        viewModel.saveConfig(onSuccess = {}, onError = { errorMessage = it })

        assertEquals(null, fakeRepository.activeConfig.value)
        assertEquals("Local model max tokens must be a positive number.", errorMessage)
    }

    @Test
    fun updateHuggingFaceToken_doesNotPersistOrDownloadUntilExplicitActions() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.LOCAL)
        viewModel.updateHuggingFaceToken("hf_partial_token")

        assertEquals(null, fakeRepository.huggingFaceToken.value)
        assertEquals(null, fakeEmbeddedModelRepository.downloadedEntry)
    }

    @Test
    fun saveConfig_persistsTrimmedHuggingFaceToken() = runTest {
        fakeRepository.activeConfigState.value = null
        viewModel.loadConfig()

        viewModel.updateMode(ServerMode.LOCAL)
        viewModel.updateHuggingFaceToken("  hf_saved_token  ")
        viewModel.saveConfig(onSuccess = {})

        assertEquals("hf_saved_token", fakeRepository.huggingFaceToken.value)
    }

    @Test
    fun embeddedModelCatalog_updatesUiStateWhenRepositoryEmitsProgress() = runTest {
        val entry = EmbeddedModelCatalogEntry(
            name = "Gemma test",
            modelId = "google/gemma-test-litert-lm",
            modelFile = "gemma-test.litertlm",
            sizeInBytes = 1024L,
            estimatedPeakMemoryInBytes = 2048L,
            defaultConfig = EmbeddedModelDefaultConfig(maxTokens = 8192, accelerators = listOf("cpu")),
            taskTypes = listOf("chat"),
        )
        fakeEmbeddedModelRepository.catalogState.value = listOf(
            EmbeddedModelCatalogItem(
                entry = entry,
                state = EmbeddedModelDownloadState.Downloading(bytesDownloaded = 512L, totalBytes = 1024L),
            )
        )

        val successState = (viewModel.uiState.value as UiState.Success).data
        assertEquals(
            EmbeddedModelDownloadState.Downloading(bytesDownloaded = 512L, totalBytes = 1024L),
            successState.embeddedModelCatalog.single().state,
        )
    }

    @Test
    fun selectEmbeddedModel_updatesLocalRuntimeConfigFields() = runTest {
        val entry = EmbeddedModelCatalogEntry(
            name = "Gemma test",
            modelId = "google/gemma-test-litert-lm",
            modelFile = "gemma-test.litertlm",
            sizeInBytes = 1024L,
            estimatedPeakMemoryInBytes = 2048L,
            defaultConfig = EmbeddedModelDefaultConfig(maxTokens = 8192, accelerators = listOf("GPU", "cpu")),
            taskTypes = listOf("chat"),
        )
        val item = EmbeddedModelCatalogItem(
            entry = entry,
            state = EmbeddedModelDownloadState.Downloaded("/data/user/0/com.letta.mobile/files/embedded-lettacode/models/gemma-test.litertlm"),
        )

        viewModel.updateMode(ServerMode.LOCAL)
        viewModel.selectEmbeddedModel(item)

        val successState = (viewModel.uiState.value as UiState.Success).data
        assertEquals(ServerMode.LOCAL, successState.mode)
        assertEquals(ConfigViewModel.LOCAL_RUNTIME_URL, successState.serverUrl)
        assertEquals("/data/user/0/com.letta.mobile/files/embedded-lettacode/models/gemma-test.litertlm", successState.localModelPath)
        assertEquals("google/gemma-test-litert-lm", successState.localModelHandle)
        assertEquals("gpu", successState.localModelAccelerator)
        assertEquals("8192", successState.localModelMaxTokens)
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

    private class FakeEmbeddedLettaCodeRuntimeStatusProvider : EmbeddedLettaCodeRuntimeStatusProvider {
        override val status: EmbeddedLettaCodeRuntimeStatus = EmbeddedLettaCodeRuntimeStatus(
            nativeEnabled = false,
            assetsEnabled = false,
            version = "disabled",
            integrity = "",
        )
    }

    private class FakeOnDeviceModelImporter : OnDeviceModelImporter {
        var nextModel: ImportedOnDeviceModel = ImportedOnDeviceModel(
            displayName = "model.litertlm",
            fileName = "model.litertlm",
            path = "/data/model.litertlm",
            handle = "local/model",
        )

        override suspend fun importModel(uri: Uri): ImportedOnDeviceModel = nextModel
    }

    private class FakeEmbeddedModelRepository : EmbeddedModelRepository {
        val catalogState = MutableStateFlow<List<EmbeddedModelCatalogItem>>(emptyList())
        override val catalog: StateFlow<List<EmbeddedModelCatalogItem>> = catalogState
        var downloadedEntry: EmbeddedModelCatalogEntry? = null
        var cancelledEntry: EmbeddedModelCatalogEntry? = null

        override suspend fun refresh() = Unit

        override suspend fun download(entry: EmbeddedModelCatalogEntry) {
            downloadedEntry = entry
        }

        override fun cancel(entry: EmbeddedModelCatalogEntry) {
            cancelledEntry = entry
        }

        override fun localPathFor(entry: EmbeddedModelCatalogEntry): String? = null
    }

    private class FakeCloudConnectionValidator(
        var result: CloudConnectionValidationResult = CloudConnectionValidationResult.Success,
    ) : CloudConnectionValidator() {
        var calls: Int = 0

        override suspend fun validate(
            baseUrl: String,
            apiToken: String,
        ): CloudConnectionValidationResult {
            calls++
            return result
        }
    }
}
