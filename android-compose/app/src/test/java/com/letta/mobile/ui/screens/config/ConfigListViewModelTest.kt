package com.letta.mobile.ui.screens.config

import android.content.Context
import app.cash.turbine.test
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeServerHealthRepository
import com.letta.mobile.testutil.TestData
import com.letta.mobile.ui.common.UiState
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class ConfigListViewModelTest {

    private lateinit var fakeRepo: FakeSettingsRepository
    private lateinit var viewModel: ConfigListViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val appContext = mockk<Context>(relaxed = true)
        fakeRepo = FakeSettingsRepository()
        // letta-mobile-aaxy: stub ServerHealthRepository instead of
        // constructing the real one. The real repo spins up a background
        // IO coroutine that fires real HTTP probes against the synthetic
        // configs, which adds non-determinism (probes can outlive the
        // test, hit DNS resolution paths, etc.) that has no bearing on
        // what these tests assert (config-mapping into uiState).
        val healthRepo = FakeServerHealthRepository()
        viewModel = ConfigListViewModel(fakeRepo, healthRepo, appContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadConfigs maps configs with active flag`() = runTest {
        val config1 = TestData.lettaConfig(id = "c1")
        val config2 = TestData.lettaConfig(id = "c2", mode = LettaConfig.Mode.SELF_HOSTED)
        val config3 = TestData.lettaConfig(id = "c3", mode = LettaConfig.Mode.LOCAL, serverUrl = ConfigViewModel.LOCAL_RUNTIME_URL)
        fakeRepo.saveConfig(config1)
        fakeRepo.saveConfig(config2)
        fakeRepo.saveConfig(config3)
        fakeRepo.setActiveConfigId("c1")
        
        viewModel.loadConfigs()
        viewModel.uiState.test {
            val state = awaitItem() as UiState.Success
            assertEquals(3, state.data.configs.size)
            assertTrue(state.data.configs.first { it.id == "c1" }.isActive)
            assertEquals(ServerMode.LOCAL, state.data.configs.first { it.id == "c3" }.mode)
        }
    }

    @Test
    fun `setActiveConfig calls repo`() = runTest {
        val config = TestData.lettaConfig(id = "c1")
        fakeRepo.saveConfig(config)
        
        viewModel.loadConfigs()
        viewModel.setActiveConfig("c1")
        assertEquals("c1", fakeRepo.activeConfig.value?.id)
    }

    @Test
    fun `deleteConfig calls repo`() = runTest {
        val config = TestData.lettaConfig(id = "c1")
        fakeRepo.saveConfig(config)
        fakeRepo.setActiveConfigId("c1")
        
        viewModel.loadConfigs()
        viewModel.deleteConfig("c1")
        assertTrue(fakeRepo.configs.value.none { it.id == "c1" })
    }

    @Test
    fun `setActiveConfig sets Error on failure`() = runTest {
        val config = TestData.lettaConfig(id = "c1")
        val failingRepo = mockk<ISettingsRepository>(relaxed = true)
        coEvery { failingRepo.setActiveConfigId(any()) } throws Exception("Failed")
        every { failingRepo.configs } returns MutableStateFlow(emptyList())
        every { failingRepo.activeConfig } returns MutableStateFlow(null)
        
        val healthRepo = FakeServerHealthRepository()
        val failViewModel = ConfigListViewModel(failingRepo, healthRepo, mockk(relaxed = true))

        failViewModel.setActiveConfig("c1")

        failViewModel.uiState.test { assertTrue(awaitItem() is UiState.Error) }
    }
}
