package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.RecentModelsPersistence
import com.letta.mobile.data.model.RecentModelsStore
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** letta-mobile-bzvro.18 (F18): desktop shares `recentModels` with the letta-code TUI's settings file. */
class DesktopRecentModelsPersistenceTest {
    @Test
    fun aPickRoundTripsThroughTheTuiFileAndKeepsItsOtherSettings() {
        val dir = Files.createTempDirectory("letta-settings")
        val file = dir.resolve("settings.json")
        file.writeText("""{"lastAgent":"agent-1","recentModels":["sonnet"],"permissions":{"allow":["Bash(ls)"]}}""")
        val fallback = MemoryPersistence()
        val store = RecentModelsStore(DesktopRecentModelsPersistence(file, fallback))

        assertEquals(listOf("sonnet"), store.recent.value)
        store.record("openai/gpt-x")

        val written = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(listOf("lastAgent", "recentModels", "permissions"), written.keys.toList())
        assertEquals("""["openai/gpt-x","sonnet"]""", written.getValue("recentModels").toString())
        assertEquals("""{"allow":["Bash(ls)"]}""", written.getValue("permissions").toString())
        assertEquals(emptyList(), fallback.saved, "the app's own store is not used while the TUI file exists")
        assertEquals(listOf(file.fileName.toString()), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun withoutTheTuiTheAppsOwnStoreIsUsedAndNoFileIsCreated() {
        val dir = Files.createTempDirectory("letta-settings")
        val file = dir.resolve("settings.json")
        val fallback = MemoryPersistence()

        RecentModelsStore(DesktopRecentModelsPersistence(file, fallback)).record("openai/gpt-x")

        assertFalse(file.exists())
        assertEquals(listOf("openai/gpt-x"), fallback.saved)
    }

    @Test
    fun aTuiFileThatIsNotASettingsObjectIsLeftAlone() {
        val file = Files.createTempDirectory("letta-settings").resolve("settings.json")
        file.writeText("not json")

        RecentModelsStore(DesktopRecentModelsPersistence(file, MemoryPersistence())).record("openai/gpt-x")

        assertTrue(file.readText() == "not json")
    }

    private class MemoryPersistence : RecentModelsPersistence {
        var saved: List<String> = emptyList()

        override fun load(): List<String> = saved

        override fun save(models: List<String>) {
            saved = models
        }
    }
}
