package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.data.plugin.PluginRegistryFixtures.manifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Declarative props migrations (plan section 3.1): rename, default and drop, keyed by the from-version. */
class PluginMigrationsTest {
    private val widget = PluginRegistryFixtures.example.elements.getValue("widget")

    private fun props(text: String) = json(text).jsonObject

    @Test
    fun theExampleBringsVersionOnePropsToVersionTwo() {
        val migrated = assertIs<PluginMigrationResult.Migrated>(PluginMigrations.toCurrent(props("""{"pct":0.5,"label":"a"}"""), 1, widget))
        assertEquals(props("""{"label":"a","progress":0.5,"status":"idle"}"""), migrated.props)
        assertEquals(2, migrated.v)
    }

    @Test
    fun aDefaultNeverOverwritesAndARenameKeepsAnExistingTarget() {
        val migrated = assertIs<PluginMigrationResult.Migrated>(PluginMigrations.toCurrent(props("""{"pct":0.1,"progress":0.9,"status":"done"}"""), 1, widget))
        assertEquals(props("""{"progress":0.9,"status":"done"}"""), migrated.props)
    }

    @Test
    fun stepsRunInVersionOrderAcrossSeveralVersions() {
        val kind = manifest(
            "/elements/widget/schemaVersion" to JsonPrimitive(4),
            "/elements/widget/migrations" to json("""{"3":[{"drop":"legacy"}],"1":[{"rename":["a","b"]}],"2":[{"rename":["b","label"]}]}"""),
        ).elements.getValue("widget")
        val migrated = assertIs<PluginMigrationResult.Migrated>(PluginMigrations.toCurrent(props("""{"a":"x","legacy":true}"""), 1, kind))
        assertEquals(props("""{"label":"x"}"""), migrated.props)
        val fromThree = assertIs<PluginMigrationResult.Migrated>(PluginMigrations.apply(props("""{"a":"x","legacy":true}"""), 3, 4, kind))
        assertEquals(props("""{"a":"x"}"""), fromThree.props)
    }

    @Test
    fun theCurrentVersionMigratesToItselfUnchanged() {
        val same = props("""{"status":"idle"}""")
        assertEquals(PluginMigrationResult.Migrated(same, 2), PluginMigrations.toCurrent(same, 2, widget))
    }

    @Test
    fun versionsOutsideTheKindAreRefused() {
        assertIs<PluginMigrationResult.Refused>(PluginMigrations.apply(props("{}"), 0, 2, widget))
        assertIs<PluginMigrationResult.Refused>(PluginMigrations.apply(props("{}"), 1, 3, widget))
        assertIs<PluginMigrationResult.Refused>(PluginMigrations.apply(props("{}"), 2, 1, widget))
    }

    @Test
    fun anUnknownStepRefuses() {
        val kind = widget.copy(migrations = mapOf("1" to listOf(PluginMigrationStep())))
        val refused = assertIs<PluginMigrationResult.Refused>(PluginMigrations.toCurrent(props("{}"), 1, kind))
        assertEquals("migration 1 step 0 is not rename, default or drop", refused.reason)
    }

    @Test
    fun aStepIsExactlyOneWellFormedOp() {
        assertEquals(PluginMigrationOp.Rename("a", "b"), PluginMigrationOp.of(PluginMigrationStep(rename = listOf("a", "b"))))
        assertEquals(PluginMigrationOp.Drop("a"), PluginMigrationOp.of(PluginMigrationStep(drop = "a")))
        assertEquals(PluginMigrationOp.Default("a", JsonPrimitive(1)), PluginMigrationOp.of(PluginMigrationStep(default = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(1))))))
        assertNull(PluginMigrationOp.of(PluginMigrationStep(rename = listOf("a", "a"))))
        assertNull(PluginMigrationOp.of(PluginMigrationStep(rename = listOf("a", "b"), drop = "c")))
        assertNull(PluginMigrationOp.of(PluginMigrationStep(default = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1))))))
    }
}
