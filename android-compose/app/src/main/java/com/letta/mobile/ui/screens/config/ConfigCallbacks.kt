package com.letta.mobile.ui.screens.config

import androidx.compose.runtime.Stable
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem

// What each settings card can ask of the screen's owner. ConfigScreen builds them once from the
// view model; each card reads only its own.

/** The server card's edits; the on-device model's live in [LocalModelCallbacks]. */
@Stable
internal class ServerSettingsCallbacks(
    val onModeChange: (ServerMode) -> Unit,
    val onServerUrlChange: (String) -> Unit,
    val onApiTokenChange: (String) -> Unit,
)

/** The on-device model and local provider field edits, shown while the mode is [ServerMode.LOCAL]. */
@Stable
internal class LocalModelCallbacks(
    val onLocalModelPathChange: (String) -> Unit,
    val onLocalModelHandleChange: (String) -> Unit,
    val onLocalModelAcceleratorChange: (String) -> Unit,
    val onLocalModelMaxTokensChange: (String) -> Unit,
    val onLocalProviderBaseUrlChange: (String) -> Unit,
    val onLocalProviderApiKeyChange: (String) -> Unit,
    val onLocalProviderModelChange: (String) -> Unit,
    val onHuggingFaceTokenChange: (String) -> Unit,
)

/** Getting a model file onto the device: importing one, or the embedded catalog's downloads. */
@Stable
internal class EmbeddedModelCallbacks(
    val onImportLocalModel: () -> Unit,
    val onDownloadEmbeddedModel: (EmbeddedModelCatalogItem) -> Unit,
    val onCancelEmbeddedModelDownload: (EmbeddedModelCatalogItem) -> Unit,
    val onSelectEmbeddedModel: (EmbeddedModelCatalogItem) -> Unit,
)

/** The appearance card's edits. */
@Stable
internal class AppearanceCallbacks(
    val onThemeChange: (AppTheme) -> Unit,
    val onThemePresetChange: (ThemePreset) -> Unit,
    val onDynamicColorChange: (Boolean) -> Unit,
)

/** The features card's switches. */
@Stable
internal class FeatureToggleCallbacks(
    val onEnableProjectsChange: (Boolean) -> Unit,
    val onHapticsEnabledChange: (Boolean) -> Unit,
    val onSharedChatPageEnabledChange: (Boolean) -> Unit,
    val onOpenChatsOnCanvasChange: (Boolean) -> Unit,
    val onSharedNavDrawerEnabledChange: (Boolean) -> Unit = {},
)
