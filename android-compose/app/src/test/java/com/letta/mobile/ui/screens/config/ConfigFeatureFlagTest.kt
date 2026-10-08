package com.letta.mobile.ui.screens.config

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.repository.api.FeatureFlag
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatusProvider
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.ui.common.UiState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

/** Settings preview switches ([FeatureFlag]) load into the form and persist when flipped (letta-mobile-c3np7.5.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class ConfigFeatureFlagTest {
    private val settings = FakeSettingsRepository()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settings.activeConfigState.value = null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): ConfigViewModel {
        val models = mockk<EmbeddedModelRepository>(relaxed = true)
        every { models.catalog } returns MutableStateFlow(emptyList())
        val runtime = mockk<EmbeddedLettaCodeRuntimeStatusProvider>()
        every { runtime.status } returns EmbeddedLettaCodeRuntimeStatus(nativeEnabled = false, assetsEnabled = false, version = "disabled", integrity = "")
        return ConfigViewModel(SavedStateHandle(), settings, mockk(relaxed = true), runtime, mockk(relaxed = true), models, mockk(relaxed = true), mockk(relaxed = true))
    }

    private fun ConfigViewModel.form(): ConfigUiState = (uiState.value as UiState.Success).data

    @Test
    fun aFlagThatIsOnLoadsIntoTheForm() = runTest {
        settings.setFeatureFlag(FeatureFlag.SharedNavDrawer, true)
        assertTrue(FeatureFlag.SharedNavDrawer in viewModel().form().enabledFeatureFlags)
    }

    @Test
    fun flippingAFlagUpdatesTheFormAndPersists() = runTest {
        val vm = viewModel()
        assertFalse(FeatureFlag.SharedNavDrawer in vm.form().enabledFeatureFlags)

        vm.updateFeatureFlag(FeatureFlag.SharedNavDrawer, true)
        assertTrue(FeatureFlag.SharedNavDrawer in vm.form().enabledFeatureFlags)
        assertEquals(true, settings.getFeatureFlag(FeatureFlag.SharedNavDrawer).first())

        vm.updateFeatureFlag(FeatureFlag.SharedNavDrawer, false)
        assertFalse(FeatureFlag.SharedNavDrawer in vm.form().enabledFeatureFlags)
        assertEquals(false, settings.getFeatureFlag(FeatureFlag.SharedNavDrawer).first())
    }
}
