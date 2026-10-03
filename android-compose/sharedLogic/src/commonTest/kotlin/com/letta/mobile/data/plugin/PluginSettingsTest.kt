package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.data.plugin.PluginRegistryFixtures.manifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Typed settings with defaults and pointer refusals (plan section 3.4). */
class PluginSettingsTest {
    private val example = manifest("/settings/mode" to json("""{"type":"enum","values":["fast","best"],"default":"fast"}"""))

    private fun validate(text: String) = PluginSettings.validate(example, json(text).jsonObject)

    private fun refusedAt(text: String): List<String> =
        assertIs<PluginSettingsResult.Invalid>(validate(text)).problems.map { "${it.path} ${it.code}" }

    @Test
    fun valuesAreFilledInWithDefaults() {
        val valid = assertIs<PluginSettingsResult.Valid>(validate("""{"baseUrl":"https://api.example.test"}"""))
        assertEquals(json("""{"quality":80,"mode":"fast","baseUrl":"https://api.example.test"}""").jsonObject, valid.settings)
    }

    @Test
    fun aValueOverridesItsDefault() {
        val valid = assertIs<PluginSettingsResult.Valid>(validate("""{"baseUrl":"https://a.test","quality":5,"mode":"best"}"""))
        assertEquals(JsonPrimitive(5), valid.settings["quality"])
        assertEquals(JsonPrimitive("best"), valid.settings["mode"])
    }

    @Test
    fun eachBadValueIsRefusedAtItsPointer() {
        assertEquals(listOf("/baseUrl MISSING_FIELD"), refusedAt("{}"))
        assertEquals(listOf("/baseUrl BAD_PATTERN"), refusedAt("""{"baseUrl":"not a uri"}"""))
        assertEquals(listOf("/quality OUT_OF_RANGE"), refusedAt("""{"baseUrl":"https://a.test","quality":0}"""))
        assertEquals(listOf("/quality WRONG_TYPE"), refusedAt("""{"baseUrl":"https://a.test","quality":2.5}"""))
        assertEquals(listOf("/mode NOT_ALLOWED"), refusedAt("""{"baseUrl":"https://a.test","mode":"slow"}"""))
        assertEquals(listOf("/other UNKNOWN_FIELD"), refusedAt("""{"baseUrl":"https://a.test","other":1}"""))
    }

    @Test
    fun theSummaryKnowsWhatIsSetAndWhatIsMissing() {
        val empty = PluginSettingsSummary.of(example, JsonObject(emptyMap()))
        assertEquals(setOf("baseUrl"), empty.missingRequired)
        assertFalse(empty.complete)
        val set = PluginSettingsSummary.of(example, json("""{"baseUrl":"https://a.test","quality":3}""").jsonObject)
        assertEquals(setOf("baseUrl", "quality"), set.configured)
        assertTrue(set.complete)
    }

    @Test
    fun aBooleanAndANumberSettingHoldTheirTypes() {
        val manifest = manifest(
            "/settings/verbose" to json("""{"type":"boolean","default":false}"""),
            "/settings/ratio" to json("""{"type":"number","minimum":0,"maximum":1}"""),
        )
        val invalid = assertIs<PluginSettingsResult.Invalid>(PluginSettings.validate(manifest, json("""{"baseUrl":"https://a.test","verbose":"yes","ratio":2}""").jsonObject))
        assertEquals(listOf("/verbose WRONG_TYPE", "/ratio OUT_OF_RANGE"), invalid.problems.map { "${it.path} ${it.code}" })
    }
}
