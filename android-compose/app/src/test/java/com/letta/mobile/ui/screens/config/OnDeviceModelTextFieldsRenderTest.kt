package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.EndpointOpenAiModelCatalog
import com.letta.mobile.runtime.local.OnDeviceModelImporter
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogEntry
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDefaultConfig
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.InMemorySecureSettingsStore
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.settings.SettingsContributionSection
import com.letta.mobile.ui.settings.SettingsSectionTags
import com.letta.mobile.ui.test.setLettaTestContent
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-by4vm: the On-device model section's text fields take typing. The real
 * [ConfigViewModel] and [androidSettingsContributions] (which build a new contribution on every
 * state change) drawn by the shared [SettingsContributionSection]: every keystroke must land in the
 * view model and stay on screen. Before the fix the section kept drawing the first model, so each
 * field stayed at its loaded value whatever was typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class OnDeviceModelTextFieldsRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val viewModel = ConfigViewModel(
        SavedStateHandle(),
        FakeSettingsRepository(),
        RunnableRuntime(),
        mockk<OnDeviceModelImporter>(relaxed = true),
        GatedModelRepository(),
        EndpointOpenAiModelCatalog(),
        FakeAppServerConnectionTester(),
        PermissionModeSettings(InMemorySecureSettingsStore()),
    )

    private fun showOnDeviceSection() {
        val inputs = AndroidSettingsInputs(viewModel, SettingsDestinations(), BatteryOptimizationState(true) {}, {}, {})
        composeRule.setLettaTestContent {
            val state by viewModel.uiState.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidSettingsContributions(state, inputs)
                    .filter { it.group == SettingsGroup.Backend }
                    .forEach { SettingsContributionSection(it) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun field(key: String): SemanticsNodeInteraction =
        composeRule.onNodeWithTag(SettingsSectionTags.ROW_PREFIX + key).performScrollTo()

    private fun typeEachCharacter(key: String, text: String, current: (ConfigUiState) -> String) {
        text.forEachIndexed { index, char ->
            field(key).performTextInput(char.toString())
            composeRule.waitForIdle()
            val typed = text.take(index + 1)
            assertEquals("view model after '$typed'", typed, current((viewModel.uiState.value as UiState.Success).data))
            field(key).assertTextEquals(typed).assertIsFocused()
        }
    }

    @Test
    fun theHuggingFaceTokenFieldTakesEachKeystroke() {
        showOnDeviceSection()
        // The token is a secret: show it so the field's text is the typed text, not bullets.
        val reveal = hasContentDescription("Show") and hasAnyAncestor(hasTestTag(SettingsSectionTags.ROW_PREFIX + "hugging-face-token"))
        composeRule.onNode(reveal, useUnmergedTree = true)
            .performClick()
        typeEachCharacter("hugging-face-token", "hf_abc") { it.huggingFaceToken }
    }

    @Test
    fun theOtherOnDeviceTextFieldsTakeEachKeystroke() {
        showOnDeviceSection()
        typeEachCharacter("model-path", "/m.gguf") { it.localModelPath }
        typeEachCharacter("provider-model", "lm/x") { it.localProviderModel }
    }

    private class RunnableRuntime : EmbeddedLettaCodeRuntimeStatusProvider {
        override val status = EmbeddedLettaCodeRuntimeStatus(nativeEnabled = true, assetsEnabled = true, version = "test", integrity = "")
    }

    /** One gated model: the first token character flips its "needs a token" note and enables Download. */
    private class GatedModelRepository : EmbeddedModelRepository {
        private val gated = EmbeddedModelCatalogEntry(
            name = "Gated model",
            modelId = "google/gated-litert-lm",
            modelFile = "gated.litertlm",
            sizeInBytes = 1024L,
            estimatedPeakMemoryInBytes = 2048L,
            defaultConfig = EmbeddedModelDefaultConfig(maxTokens = 4096, accelerators = listOf("cpu")),
            taskTypes = listOf("chat"),
            requiresAuth = true,
        )
        override val catalog: StateFlow<List<EmbeddedModelCatalogItem>> =
            MutableStateFlow(listOf(EmbeddedModelCatalogItem(gated, EmbeddedModelDownloadState.Idle)))
        override suspend fun refresh() = Unit
        override suspend fun download(entry: EmbeddedModelCatalogEntry) = Unit
        override fun cancel(entry: EmbeddedModelCatalogEntry) = Unit
        override fun localPathFor(entry: EmbeddedModelCatalogEntry): String? = null
    }
}
