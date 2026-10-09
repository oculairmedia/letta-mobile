package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.transport.appserver.AppServerExternalToolDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-jna0o.1: every model request carries the tool definitions the controller advertises
 * in `runtime_start.external_tools`, so their size is paid on every turn. This holds each canvas
 * tool, serialised exactly as it is sent ({name, description, parameters}), to a character budget
 * (tokens ~ chars / 3.5), so a definition cannot quietly grow back. Teach the model a format in
 * `canvas_compose_guide` (fetched on demand), not in a definition.
 *
 * Raise a budget only with a reason that outweighs the per-turn cost for every agent.
 */
class CanvasToolTokenBudgetTest {
    private val wire = Json { encodeDefaults = true }

    /** The wire size of each advertised canvas tool, plus the renderer-gated preview tool. */
    private fun sizes(): Map<String, Int> {
        val registry = ExternalToolRegistry.hostTools(
            CanvasExternalTools.all(InMemoryCanvasDocumentStore(), CanvasSessionRegistry()),
        )
        val sent = registry.advertisedToolsCommandGroups().orEmpty().flatMap { it.tools }
        val preview = CanvasToolContract.renderPreview.let {
            AppServerExternalToolDefinition(it.name, it.description, it.inputSchema)
        }
        return (sent + preview).associate { it.name to wire.encodeToString(it).length }
    }

    private fun tokens(chars: Int): Int = (chars / CHARS_PER_TOKEN).toInt()

    @Test
    fun everyCanvasToolStaysWithinItsBudget() {
        val sizes = sizes()
        val advertisedTotal = sizes.filterKeys { it != CanvasToolContract.RENDER_PREVIEW }.values.sum()
        val table = buildString {
            appendLine("tool                         chars  ~tokens  budget")
            sizes.forEach { (name, chars) ->
                val budget = BUDGET_CHARS[name] ?: "UNBUDGETED"
                appendLine("${name.padEnd(28)} ${chars.toString().padStart(5)}  ${tokens(chars).toString().padStart(7)}  $budget")
            }
            appendLine("${"TOTAL (advertised)".padEnd(28)} ${advertisedTotal.toString().padStart(5)}  ${tokens(advertisedTotal).toString().padStart(7)}  $TOTAL_BUDGET_CHARS")
        }
        println(table)
        val unbudgeted = sizes.keys - BUDGET_CHARS.keys
        assertTrue(unbudgeted.isEmpty(), "Add a budget for $unbudgeted in CanvasToolTokenBudgetTest\n$table")
        val over = sizes.filter { (name, chars) -> chars > BUDGET_CHARS.getValue(name) }
        assertTrue(over.isEmpty(), "Over the per-tool budget: ${over.keys}\n$table")
        assertTrue(advertisedTotal <= TOTAL_BUDGET_CHARS, "Total $advertisedTotal chars is over $TOTAL_BUDGET_CHARS\n$table")
    }

    /**
     * canvas_apply_ops points at canvas_replace_scene's description for the scene format instead of
     * repeating it, so the two must always be advertised together (app tools and host tools alike).
     */
    @Test
    fun applyOpsIsAlwaysAdvertisedWithReplaceSceneWhichCarriesTheSceneFormat() {
        val store = InMemoryCanvasRelayStore()
        val backend = HostCanvasBackend(CanvasRelayHost(store, hostId = { "host-1" }), store, InMemoryHostCanvasDirectory())
        val advertised = mapOf(
            "CanvasExternalTools" to CanvasExternalTools.all(InMemoryCanvasDocumentStore(), CanvasSessionRegistry()).map { it.name },
            "HostCanvasTools" to HostCanvasTools.all(backend).map { it.name },
        )
        advertised.forEach { (source, names) ->
            assertTrue(CanvasToolContract.APPLY_OPS in names, "$source advertises apply_ops")
            assertTrue(CanvasToolContract.REPLACE_SCENE in names, "$source must advertise replace_scene beside apply_ops: $names")
        }
        assertTrue(CanvasToolContract.REPLACE_SCENE in CanvasToolContract.applyOps.description, "apply_ops points at replace_scene")
        // scene_json's own description is short because the shape is here.
        assertTrue("{\"bgColor\":\"#rrggbbaa\",\"elements\":[...]}" in CanvasToolContract.replaceScene.description)
    }

    @Test
    fun theComposeColorKeepsItsPresetNamesInTheSchema() {
        val schema = CanvasToolContract.compose.inputSchema.toString()
        assertTrue("red, orange, yellow, green, cyan, purple or #rrggbb" in schema, "a provider that ignores pattern still sees the presets")
    }

    private companion object {
        const val CHARS_PER_TOKEN = 3.5

        val BUDGET_CHARS: Map<String, Int> = mapOf(
            "canvas_create" to 340,
            "canvas_get_scene" to 550,
            "canvas_get_layout" to 1280,
            "canvas_replace_scene" to 4400,
            "canvas_apply_ops" to 3350,
            "canvas_list" to 380,
            // 6800: the colour schema repeats its preset names (letta-mobile-jna0o.1 review) for providers that ignore `pattern`.
            "canvas_compose" to 6800,
            "canvas_compose_guide" to 340,
            "canvas_render_preview" to 1250,
        )
        const val TOTAL_BUDGET_CHARS = 17100
    }
}
