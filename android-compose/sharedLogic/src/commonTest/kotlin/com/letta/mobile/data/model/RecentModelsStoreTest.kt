package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** letta-mobile-bzvro.18 (F18): the recent-models list and the TUI settings file it shares. */
class RecentModelsStoreTest {
    @Test
    fun recordingMovesAModelToTheFrontWithoutDuplicates() {
        val persisted = ListPersistence(mutableListOf("b", "a"))
        val store = RecentModelsStore(persisted)

        store.record("a")
        store.record("c")

        assertEquals(listOf("c", "a", "b"), store.recent.value)
        assertEquals(listOf("c", "a", "b"), persisted.saved)
    }

    @Test
    fun thePickerSeesEightAndTheFileKeepsTen() {
        val persisted = ListPersistence(mutableListOf())
        val store = RecentModelsStore(persisted)

        (1..12).forEach { store.record("m$it") }

        assertEquals((12 downTo 5).map { "m$it" }, store.recent.value)
        assertEquals((12 downTo 3).map { "m$it" }, persisted.saved)
    }

    @Test
    fun aRecordMergesWhatAnotherClientSavedMeanwhile() {
        val persisted = ListPersistence(mutableListOf("a"))
        val store = RecentModelsStore(persisted)
        persisted.saved = listOf("tui-pick", "a")

        store.record("b")

        assertEquals(listOf("b", "tui-pick", "a"), store.recent.value)
    }

    @Test
    fun aBrokenStoreReadsAsEmptyAndBlankHandlesAreIgnored() {
        val store = RecentModelsStore(object : RecentModelsPersistence {
            override fun load(): List<String> = error("unreadable")
            override fun save(models: List<String>) = error("read-only")
        })

        store.record(" ")
        store.record("a")

        assertEquals(listOf("a"), store.recent.value)
    }

    @Test
    fun theSettingsStoreKeepsOneHandlePerLine() {
        val store = com.letta.mobile.data.chat.runtime.MapSettingsStore()
        val persistence = SettingsStoreRecentModels(store)
        persistence.save(listOf("openai/gpt-x", "anthropic/claude"))

        assertEquals(listOf("openai/gpt-x", "anthropic/claude"), persistence.load())
        persistence.save(emptyList())
        assertNull(store.getString(SettingsStoreRecentModels.DEFAULT_KEY))
    }

    @Test
    fun theTuiFileRoundTripsAndKeepsEveryOtherKeyInPlace() {
        val original = """
            {
              "lastAgent": "agent-1",
              "recentModels": ["sonnet"],
              "agents": [{"agentId": "agent-1", "memfs": true}],
              "futureKey": {"nested": [1, 2]}
            }
        """.trimIndent()

        val merged = LettaSettingsRecentModels.merge(original, listOf("openai/gpt-x", "sonnet"))!!
        val parsed = Json.parseToJsonElement(merged).jsonObject

        assertEquals(listOf("lastAgent", "recentModels", "agents", "futureKey"), parsed.keys.toList())
        assertEquals(listOf("openai/gpt-x", "sonnet"), LettaSettingsRecentModels.read(merged))
        assertEquals(Json.parseToJsonElement(original).jsonObject["agents"], parsed["agents"])
        assertEquals(Json.parseToJsonElement(original).jsonObject["futureKey"], parsed["futureKey"])
        assertEquals("agent-1", parsed["lastAgent"]?.jsonPrimitive?.content)
    }

    @Test
    fun aMissingKeyIsAddedAndAMissingFileStartsFresh() {
        val merged = LettaSettingsRecentModels.merge("""{"theme":"dark"}""", listOf("a"))!!
        val parsed = Json.parseToJsonElement(merged).jsonObject
        assertEquals(listOf("theme", "recentModels"), parsed.keys.toList())

        val fresh = Json.parseToJsonElement(LettaSettingsRecentModels.merge(null, listOf("a"))!!).jsonObject
        assertEquals(1, fresh.getValue("recentModels").jsonArray.size)
    }

    @Test
    fun aFileThatIsNotASettingsObjectIsNeverOverwritten() {
        assertNull(LettaSettingsRecentModels.merge("[1,2,3]", listOf("a")))
        assertNull(LettaSettingsRecentModels.merge("{not json", listOf("a")))
        assertTrue(LettaSettingsRecentModels.read("{not json").isEmpty())
        assertTrue(LettaSettingsRecentModels.read("""{"recentModels": [1, "a", null]}""") == listOf("a"))
    }

    @Test
    fun theWrittenFileIsIndentedLikeTheTuiWritesIt() {
        val merged = LettaSettingsRecentModels.merge("""{"a":1}""", listOf("m"))!!
        assertTrue(merged.startsWith("{\n  \"a\": 1"), merged)
        assertTrue(Json.parseToJsonElement(merged) is JsonObject)
    }

    private class ListPersistence(var saved: List<String>) : RecentModelsPersistence {
        override fun load(): List<String> = saved

        override fun save(models: List<String>) {
            saved = models
        }
    }
}
