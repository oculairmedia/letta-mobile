package com.letta.mobile.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.ThemePreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
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
    fun `saveConfig adds new config and sets as active`() = runTest {
        val config = LettaConfig(
            id = "c1",
            mode = LettaConfig.Mode.CLOUD,
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
        val config = LettaConfig(id = "c1", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://old.com")
        repository.saveConfig(config)

        val updated = config.copy(serverUrl = "https://new.com")
        repository.saveConfig(updated)

        assertEquals(1, repository.configs.value.size)
        assertEquals("https://new.com", repository.configs.value.first().serverUrl)
    }

    @Test
    fun `setActiveConfigId changes active config`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://one.com")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "http://two.com")
        repository.saveConfig(c1)
        repository.saveConfig(c2)

        repository.setActiveConfigId("c1")
        assertEquals("c1", repository.activeConfig.value?.id)
    }

    @Test
    fun `setActiveConfigId with nonexistent id does nothing`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://one.com")
        repository.saveConfig(c1)

        repository.setActiveConfigId("nonexistent")
        assertEquals("c1", repository.activeConfig.value?.id)
    }

    @Test
    fun `deleteConfig removes config`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://one.com")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://two.com")
        repository.saveConfig(c1)
        repository.saveConfig(c2)

        repository.deleteConfig("c1")

        assertEquals(1, repository.configs.value.size)
        assertTrue(repository.configs.value.none { it.id == "c1" })
    }

    @Test
    fun `deleteConfig clears active if deleted was active`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://one.com")
        repository.saveConfig(c1)
        assertEquals("c1", repository.activeConfig.value?.id)

        repository.deleteConfig("c1")

        assertNull(repository.activeConfig.value)
    }

    @Test
    fun `deleteConfig switches active to first remaining`() = runTest {
        val c1 = LettaConfig(id = "c1", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://one.com")
        val c2 = LettaConfig(id = "c2", mode = LettaConfig.Mode.CLOUD, serverUrl = "https://two.com")
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
        repository.setTheme(AppTheme.DARK)
        val theme = repository.getTheme().first()
        assertEquals(AppTheme.DARK, theme)
    }

    @Test
    fun `getThemePreset returns DEFAULT by default`() = runTest {
        val preset = repository.getThemePreset().first()
        assertEquals(ThemePreset.DEFAULT, preset)
    }

    @Test
    fun `setThemePreset persists preset`() = runTest {
        repository.setThemePreset(ThemePreset.AMOLED_BLACK)

        val preset = repository.getThemePreset().first()
        assertEquals(ThemePreset.AMOLED_BLACK, preset)
    }

    @Test
    fun `legacy amoled dark mode maps to amoled preset`() = runTest {
        repository.setAmoledDarkMode(true)

        val preset = repository.getThemePreset().first()
        assertEquals(ThemePreset.AMOLED_BLACK, preset)
    }

    @Test
    fun `dynamic color defaults on for default preset on android 12 plus`() = runTest {
        val dynamicColor = repository.getDynamicColor().first()
        assertTrue(dynamicColor)
    }

    @Test
    fun `setDynamicColor persists selection`() = runTest {
        repository.setDynamicColor(false)

        val dynamicColor = repository.getDynamicColor().first()
        assertEquals(false, dynamicColor)
    }
}
