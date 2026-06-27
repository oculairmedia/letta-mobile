package com.letta.mobile.ui.screens.config

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.letta.mobile.data.api.CloudConnectionValidationResult
import com.letta.mobile.data.api.CloudConnectionValidator
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.modelvalidation.ModelHandleValidator
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.EndpointOpenAiModelCatalog
import com.letta.mobile.runtime.local.OnDeviceModelImporter
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.navigation.ConfigRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ServerMode {
    CLOUD,
    SELF_HOSTED,
    LOCAL,
}

@androidx.compose.runtime.Immutable
data class ConfigUiState(
    val mode: ServerMode = ServerMode.CLOUD,
    val serverUrl: String = "",
    val apiToken: String = "",
    val theme: AppTheme = AppTheme.SYSTEM,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val dynamicColor: Boolean = false,
    val enableProjects: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val localModelPath: String = "",
    val localModelHandle: String = ConfigViewModel.DEFAULT_LOCAL_MODEL_HANDLE,
    val localModelAccelerator: String = ConfigViewModel.DEFAULT_LOCAL_MODEL_ACCELERATOR,
    val localModelMaxTokens: String = ConfigViewModel.DEFAULT_LOCAL_MODEL_MAX_TOKENS,
    val localProviderBaseUrl: String = "",
    val localProviderApiKey: String = "",
    val localProviderModel: String = "",
    val huggingFaceToken: String = "",
    val savedHuggingFaceToken: String = "",
    val isImportingLocalModel: Boolean = false,
    val embeddedModelCatalog: List<EmbeddedModelCatalogItem> = emptyList(),
    val embeddedRuntimeStatus: EmbeddedLettaCodeRuntimeStatus = EmbeddedLettaCodeRuntimeStatus(
        nativeEnabled = false,
        assetsEnabled = false,
        version = "disabled",
        integrity = "",
    ),
    val isSaving: Boolean = false,
)

@HiltViewModel
class ConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settingsRepository: ISettingsRepository,
    private val cloudConnectionValidator: CloudConnectionValidator,
    private val embeddedRuntimeStatusProvider: EmbeddedLettaCodeRuntimeStatusProvider,
    private val onDeviceModelImporter: OnDeviceModelImporter,
    private val embeddedModelRepository: EmbeddedModelRepository,
    private val endpointOpenAiModelCatalog: EndpointOpenAiModelCatalog,
) : ViewModel() {

    companion object {
        const val DEFAULT_CLOUD_URL = "https://api.letta.com"
        const val LOCAL_RUNTIME_URL = "local-lettacode://device"
        const val DEFAULT_LOCAL_MODEL_HANDLE = "local/model"
        const val DEFAULT_LOCAL_MODEL_RUNTIME = "litert-lm"
        const val DEFAULT_LOCAL_MODEL_ACCELERATOR = "cpu"
        const val DEFAULT_LOCAL_MODEL_MAX_TOKENS = "4096"
        private const val LEGACY_LOCAL_RUNTIME_URL = "local://device"
    }

    // letta-mobile-cdlk: when the route arg signals create-new mode, the
    // screen opens with an empty form and saveConfig mints a fresh UUID
    // instead of overwriting the currently active config. Defaults to false
    // so every existing nav site keeps the original "edit active" behaviour.
    private val createNew: Boolean =
        runCatching { savedStateHandle.toRoute<ConfigRoute>().createNew }.getOrDefault(false)

    private val _uiState = MutableStateFlow<UiState<ConfigUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<ConfigUiState>> = _uiState.asStateFlow()

    init {
        loadConfig()
        observeEmbeddedModelCatalog()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val activeConfig = settingsRepository.activeConfig.value
                val appTheme = settingsRepository.getTheme().first()
                val themePreset = settingsRepository.getThemePreset().first()
                val dynamicColor = settingsRepository.getDynamicColor().first()
                val enableProjects = settingsRepository.getEnableProjects().first()
                val hapticsEnabled = settingsRepository.getHapticsEnabled().first()
                val configUiState = if (activeConfig != null && !createNew) {
                    ConfigUiState(
                        mode = activeConfig.mode.toServerMode(),
                        serverUrl = activeConfig.serverUrl,
                        apiToken = activeConfig.accessToken ?: "",
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                        enableProjects = enableProjects,
                        hapticsEnabled = hapticsEnabled,
                        localModelPath = activeConfig.localModelPath.orEmpty(),
                        localModelHandle = activeConfig.localModelHandle.normalizedLocalModelHandle(),
                        localModelAccelerator = activeConfig.localModelAccelerator.normalizedLocalModelAccelerator(),
                        localModelMaxTokens = activeConfig.localModelMaxTokens.normalizedLocalModelMaxTokens(),
                        localProviderBaseUrl = activeConfig.localProviderBaseUrl.orEmpty(),
                        localProviderApiKey = activeConfig.localProviderApiKey.orEmpty(),
                        localProviderModel = activeConfig.localProviderModel.orEmpty(),
                        huggingFaceToken = settingsRepository.huggingFaceToken.value.orEmpty(),
                        savedHuggingFaceToken = settingsRepository.huggingFaceToken.value.orEmpty(),
                        embeddedModelCatalog = embeddedModelRepository.catalog.value,
                        embeddedRuntimeStatus = embeddedRuntimeStatusProvider.status,
                    )
                } else {
                    // createNew = true: empty form, fresh UUID at save time.
                    // OR there's no active config yet (first-time setup).
                    // Mode falls through to ConfigUiState's default (CLOUD)
                    // — the existing first-time setup expects that default,
                    // and the user toggles to SELF_HOSTED inline if needed.
                    ConfigUiState(
                        theme = appTheme,
                        themePreset = themePreset,
                        dynamicColor = dynamicColor,
                        enableProjects = enableProjects,
                        hapticsEnabled = hapticsEnabled,
                        huggingFaceToken = settingsRepository.huggingFaceToken.value.orEmpty(),
                        savedHuggingFaceToken = settingsRepository.huggingFaceToken.value.orEmpty(),
                        embeddedModelCatalog = embeddedModelRepository.catalog.value,
                        embeddedRuntimeStatus = embeddedRuntimeStatusProvider.status,
                    )
                }
                _uiState.value = UiState.Success(configUiState)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load config")
            }
        }
    }

    fun updateMode(mode: ServerMode) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        val updatedUrl = when (mode) {
            ServerMode.CLOUD -> DEFAULT_CLOUD_URL
            ServerMode.LOCAL -> LOCAL_RUNTIME_URL
            ServerMode.SELF_HOSTED ->
                if (currentState.serverUrl in setOf(DEFAULT_CLOUD_URL, LOCAL_RUNTIME_URL, LEGACY_LOCAL_RUNTIME_URL)) {
                    ""
                } else {
                    currentState.serverUrl
                }
        }
        _uiState.value = UiState.Success(
            currentState.copy(mode = mode, serverUrl = updatedUrl)
        )
    }

    fun updateServerUrl(url: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(serverUrl = url))
        }
    }

    fun updateApiToken(token: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(apiToken = token))
        }
    }

    fun updateTheme(theme: AppTheme) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(theme = theme))
            viewModelScope.launch {
                settingsRepository.setTheme(theme)
            }
        }
    }

    fun updateThemePreset(themePreset: ThemePreset) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            val dynamicColor = if (themePreset == ThemePreset.DEFAULT) currentState.dynamicColor else false
            _uiState.value = UiState.Success(
                currentState.copy(
                    themePreset = themePreset,
                    dynamicColor = dynamicColor,
                )
            )
            viewModelScope.launch {
                settingsRepository.setThemePreset(themePreset)
                settingsRepository.setDynamicColor(dynamicColor)
            }
        }
    }

    fun updateDynamicColor(enabled: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(dynamicColor = enabled))
            viewModelScope.launch {
                settingsRepository.setDynamicColor(enabled)
            }
        }
    }

    fun updateEnableProjects(enabled: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(enableProjects = enabled))
            viewModelScope.launch {
                settingsRepository.setEnableProjects(enabled)
            }
        }
    }

    fun updateHapticsEnabled(enabled: Boolean) {
        val currentState = (_uiState.value as? UiState.Success)?.data
        if (currentState != null) {
            _uiState.value = UiState.Success(currentState.copy(hapticsEnabled = enabled))
            viewModelScope.launch {
                settingsRepository.setHapticsEnabled(enabled)
            }
        }
    }

    fun updateLocalModelPath(path: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(localModelPath = path))
    }

    fun updateLocalModelHandle(handle: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(
            currentState.copy(
                localModelHandle = handle,
                localProviderBaseUrl = "",
                localProviderApiKey = "",
                localProviderModel = "",
            )
        )
    }

    fun updateLocalModelAccelerator(accelerator: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(localModelAccelerator = accelerator))
    }

    fun updateLocalModelMaxTokens(maxTokens: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(localModelMaxTokens = maxTokens))
    }

    fun updateLocalProviderBaseUrl(baseUrl: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(localProviderBaseUrl = baseUrl))
    }

    fun updateLocalProviderApiKey(apiKey: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(localProviderApiKey = apiKey))
    }

    fun updateLocalProviderModel(model: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(localProviderModel = model))
    }

    fun updateHuggingFaceToken(token: String) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        _uiState.value = UiState.Success(currentState.copy(huggingFaceToken = token))
    }

    fun downloadEmbeddedModel(item: EmbeddedModelCatalogItem) {
        viewModelScope.launch {
            val state = (_uiState.value as? UiState.Success)?.data
            val token = state?.huggingFaceToken?.trim().orEmpty()
            if (item.entry.requiresAuth && token.isNotBlank() && token != state?.savedHuggingFaceToken?.trim().orEmpty()) {
                settingsRepository.setHuggingFaceToken(token)
                val latest = (_uiState.value as? UiState.Success)?.data ?: state
                if (latest != null) {
                    _uiState.value = UiState.Success(latest.copy(savedHuggingFaceToken = token))
                }
            }
            embeddedModelRepository.download(item.entry)
        }
    }

    fun cancelEmbeddedModelDownload(item: EmbeddedModelCatalogItem) {
        embeddedModelRepository.cancel(item.entry)
    }

    fun selectEmbeddedModel(item: EmbeddedModelCatalogItem) {
        val currentState = (_uiState.value as? UiState.Success)?.data ?: return
        val downloaded = item.state as? EmbeddedModelDownloadState.Downloaded ?: return
        _uiState.value = UiState.Success(
            currentState.copy(
                mode = ServerMode.LOCAL,
                serverUrl = LOCAL_RUNTIME_URL,
                localModelPath = downloaded.localPath,
                localModelHandle = item.entry.modelId,
                localModelAccelerator = item.entry.primaryAccelerator,
                localModelMaxTokens = item.entry.defaultConfig.maxTokens.toString(),
                // letta-mobile-ajcrx: an ON-DEVICE LiteRT model must NOT carry a
                // custom provider base URL — a non-blank localProviderBaseUrl makes
                // isCustomProvider=true, which SKIPS the on-device loopback bridge
                // and routes the turn to the remote proxy (-> "model: <id>"
                // local_backend_error, the model isn't served remotely). Clear it
                // so the selection routes to the on-device LiteRT engine.
                localProviderBaseUrl = "",
                localProviderApiKey = "",
                localProviderModel = "",
            )
        )
        autoPersistLocalModelSelection()
    }

    // letta-mobile-qmrs8: selecting/importing a local model only mutated form
    // state; leaving the screen without tapping Save silently lost the choice
    // and local turns later failed with "requires an imported .litertlm model
    // path". Persist immediately — in place when the active config is already
    // local, as a new local entry otherwise so a remote backend entry is
    // never converted as a side effect of tapping a catalog model.
    private fun autoPersistLocalModelSelection() {
        val activeMode = settingsRepository.activeConfig.value?.mode
        saveConfig(
            onSuccess = {},
            onError = null,
            asNewEntry = createNew || (activeMode != null && activeMode != LettaConfig.Mode.LOCAL),
        )
    }

    fun importLocalModel(
        uri: Uri,
        onSuccess: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
            if (state.isImportingLocalModel) return@launch
            _uiState.value = UiState.Success(state.copy(isImportingLocalModel = true))
            try {
                val imported = onDeviceModelImporter.importModel(uri)
                val latest = (_uiState.value as? UiState.Success)?.data ?: state
                _uiState.value = UiState.Success(
                    latest.copy(
                        mode = ServerMode.LOCAL,
                        serverUrl = LOCAL_RUNTIME_URL,
                        localModelPath = imported.path,
                        localModelHandle = imported.handle,
                        isImportingLocalModel = false,
                    )
                )
                autoPersistLocalModelSelection()
                onSuccess?.invoke(imported.fileName)
            } catch (e: Exception) {
                val latest = (_uiState.value as? UiState.Success)?.data ?: state
                _uiState.value = UiState.Success(latest.copy(isImportingLocalModel = false))
                onError?.invoke(e.message ?: "Failed to import local model.")
            }
        }
    }

    private fun observeEmbeddedModelCatalog() {
        viewModelScope.launch {
            embeddedModelRepository.refresh()
            embeddedModelRepository.catalog.collect { catalog ->
                val currentState = (_uiState.value as? UiState.Success)?.data ?: return@collect
                _uiState.value = UiState.Success(currentState.copy(embeddedModelCatalog = catalog))
            }
        }
    }

    fun saveConfig(
        onSuccess: () -> Unit,
        onError: ((String) -> Unit)? = null,
        asNewEntry: Boolean = createNew,
    ) {
        viewModelScope.launch {
            val state = (_uiState.value as? UiState.Success)?.data ?: return@launch
            if (state.isSaving) return@launch
            try {
                val url = when (state.mode) {
                    ServerMode.CLOUD -> DEFAULT_CLOUD_URL
                    ServerMode.LOCAL -> LOCAL_RUNTIME_URL
                    ServerMode.SELF_HOSTED -> {
                        val raw = state.serverUrl.trim()
                        if (raw.isBlank()) {
                            onError?.invoke("Server URL is required")
                            return@launch
                        }
                        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("iroh://")) raw
                        else "https://$raw"
                    }
                }
                _uiState.value = UiState.Success(state.copy(isSaving = true))
                val apiToken = state.apiToken.trim()
                val localModelMaxTokens = if (state.mode == ServerMode.LOCAL) {
                    val rawMaxTokens = state.localModelMaxTokens.trim()
                    if (rawMaxTokens.isBlank()) {
                        DEFAULT_LOCAL_MODEL_MAX_TOKENS.toInt()
                    } else {
                        rawMaxTokens.toIntOrNull()?.takeIf { it > 0 } ?: run {
                            _uiState.value = UiState.Success(state.copy(isSaving = false))
                            onError?.invoke("Local model max tokens must be a positive number.")
                            return@launch
                        }
                    }
                } else {
                    null
                }
                val trimmedProviderBaseUrl = state.localProviderBaseUrl.trim()
                if (state.mode == ServerMode.LOCAL && trimmedProviderBaseUrl.isNotBlank() &&
                    !trimmedProviderBaseUrl.startsWith("http://") && !trimmedProviderBaseUrl.startsWith("https://")
                ) {
                    _uiState.value = UiState.Success(state.copy(isSaving = false))
                    onError?.invoke("Custom provider URL must start with http:// or https://.")
                    return@launch
                }
                val modelValidation = validateModelSelection(
                    state = state,
                    trimmedProviderBaseUrl = trimmedProviderBaseUrl,
                )
                if (modelValidation is ModelHandleValidator.Result.Invalid) {
                    _uiState.value = UiState.Success(state.copy(isSaving = false))
                    onError?.invoke(modelValidation.reason)
                    return@launch
                }
                if (state.mode == ServerMode.CLOUD) {
                    if (apiToken.isBlank()) {
                        _uiState.value = UiState.Success(state.copy(isSaving = false))
                        onError?.invoke(
                            "Letta Cloud API key is required. Paste a key from app.letta.com before saving."
                        )
                        return@launch
                    }
                    when (val validation = cloudConnectionValidator.validate(url, apiToken)) {
                        CloudConnectionValidationResult.Success -> Unit
                        is CloudConnectionValidationResult.Failed -> {
                            _uiState.value = UiState.Success(state.copy(isSaving = false))
                            onError?.invoke(validation.message)
                            return@launch
                        }
                    }
                }
                settingsRepository.setHuggingFaceToken(state.huggingFaceToken.trim().ifBlank { null })
                // letta-mobile-cdlk: asNewEntry bypasses the activeConfig
                // id lookup so '+ Add server' in the backend-switcher sheet
                // actually creates a new entry instead of overwriting the
                // currently active backend.
                val reuseId = if (asNewEntry) null else settingsRepository.activeConfig.value?.id
                val config = LettaConfig(
                    id = reuseId ?: UUID.randomUUID().toString(),
                    mode = state.mode.toLettaMode(),
                    serverUrl = url,
                    accessToken = if (state.mode == ServerMode.LOCAL) null else apiToken.ifBlank { null },
                    localModelPath = if (state.mode == ServerMode.LOCAL) {
                        state.localModelPath.trim().ifBlank { null }
                    } else {
                        null
                    },
                    localModelHandle = if (state.mode == ServerMode.LOCAL) {
                        state.localModelHandle.trim().ifBlank { DEFAULT_LOCAL_MODEL_HANDLE }
                    } else {
                        null
                    },
                    localModelRuntime = if (state.mode == ServerMode.LOCAL) DEFAULT_LOCAL_MODEL_RUNTIME else null,
                    localModelAccelerator = if (state.mode == ServerMode.LOCAL) {
                        state.localModelAccelerator.normalizedLocalModelAccelerator()
                    } else {
                        null
                    },
                    localModelMaxTokens = localModelMaxTokens,
                    localProviderBaseUrl = if (state.mode == ServerMode.LOCAL) {
                        state.localProviderBaseUrl.trim().trimEnd('/').ifBlank { null }
                    } else {
                        null
                    },
                    localProviderApiKey = if (state.mode == ServerMode.LOCAL) {
                        state.localProviderApiKey.trim().ifBlank { null }
                    } else {
                        null
                    },
                    localProviderModel = if (state.mode == ServerMode.LOCAL) {
                        state.localProviderModel.trim().ifBlank { null }
                    } else {
                        null
                    },
                )
                settingsRepository.saveConfig(config)
                // Clear isSaving on success too: auto-persist callers
                // (selectEmbeddedModel/importLocalModel) keep the screen open,
                // and a stuck flag short-circuits every subsequent save.
                val latest = (_uiState.value as? UiState.Success)?.data ?: state
                _uiState.value = UiState.Success(latest.copy(isSaving = false))
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = UiState.Success(state.copy(isSaving = false))
                onError?.invoke(e.message ?: "Failed to save config")
            }
        }
    }

    private suspend fun validateModelSelection(
        state: ConfigUiState,
        trimmedProviderBaseUrl: String,
    ): ModelHandleValidator.Result {
        if (state.mode != ServerMode.LOCAL) return ModelHandleValidator.Result.Valid

        val providerModel = state.localProviderModel.trim()
        if (trimmedProviderBaseUrl.isNotBlank() || providerModel.startsWith("lmstudio/", ignoreCase = true)) {
            val servedModels = endpointOpenAiModelCatalog.listServedModelIdsOrNull(
                baseUrl = trimmedProviderBaseUrl,
                apiKey = state.localProviderApiKey.trim().ifBlank { null },
            )
            return ModelHandleValidator.validate(
                handle = providerModel,
                backend = ModelHandleValidator.Backend.REMOTE,
                customBaseUrl = trimmedProviderBaseUrl,
                servedModels = servedModels,
            )
        }

        return ModelHandleValidator.validate(
            handle = state.localModelHandle,
            backend = ModelHandleValidator.Backend.ON_DEVICE,
            onDeviceModelPath = state.localModelPath,
        )
    }
}

private fun LettaConfig.Mode.toServerMode(): ServerMode = when (this) {
    LettaConfig.Mode.CLOUD -> ServerMode.CLOUD
    LettaConfig.Mode.SELF_HOSTED -> ServerMode.SELF_HOSTED
    LettaConfig.Mode.LOCAL -> ServerMode.LOCAL
}

private fun ServerMode.toLettaMode(): LettaConfig.Mode = when (this) {
    ServerMode.CLOUD -> LettaConfig.Mode.CLOUD
    ServerMode.SELF_HOSTED -> LettaConfig.Mode.SELF_HOSTED
    ServerMode.LOCAL -> LettaConfig.Mode.LOCAL
}

private fun String?.normalizedLocalModelHandle(): String =
    this?.trim()?.takeIf { it.isNotBlank() } ?: ConfigViewModel.DEFAULT_LOCAL_MODEL_HANDLE

private fun String?.normalizedLocalModelAccelerator(): String =
    this?.trim()
        ?.lowercase()
        ?.takeIf { it in LOCAL_MODEL_ACCELERATORS }
        ?: ConfigViewModel.DEFAULT_LOCAL_MODEL_ACCELERATOR

private fun Int?.normalizedLocalModelMaxTokens(): String =
    this?.takeIf { it > 0 }?.toString() ?: ConfigViewModel.DEFAULT_LOCAL_MODEL_MAX_TOKENS

private val LOCAL_MODEL_ACCELERATORS = setOf("cpu", "gpu", "npu")
