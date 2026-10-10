package com.letta.mobile.data.meridian

import com.letta.mobile.data.canvas.CanvasSceneSchema
import com.letta.mobile.data.canvas.CanvasToolContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The help tree (letta-mobile-jna0o.3) is generated from the tools' own definitions: these goldens
 * pin its shape, and the contract checks prove a verb's help and schema are the tool's, verbatim.
 */
class MeridianHelpTest {
    private val host = MeridianTestHost()
    private val router = host.router()

    private suspend fun run(vararg argv: String): MeridianResponse = router.execute(MeridianRequest(argv.toList(), caller = MeridianTestHost.CALLER))

    @Test
    fun topLevelHelpIsGolden() = runTest {
        val expected = """
            |meridian: commands for this conversation's host. Input is one JSON object on stdin; output is JSON.
            |
            |Commands:
            |  canvas          Read and change this conversation's canvas (notes, checklists, cards, diagrams).
            |  agents          Find other agents by name, role, capability or host.
            |  agent-message   Send a message to another agent.
            |  plugin          Run an installed plugin's agent actions.
            |  tool            Run any other tool this host serves, by name.
            |  guide           Reference text on demand: compose, scene, ops.
            |
            |Run `meridian <command> --help` for its verbs, `meridian schema <command...>` for an input schema.
            |Exit codes: 0 ok, 2 refused input, 3 denied, 4 host unavailable. Errors print {"error": ...}.
            |
        """.trimMargin()
        listOf(run("--help"), run(), run("help"), run("meridian", "-h")).forEach { response ->
            assertEquals(MeridianExit.OK, response.exitCode)
            assertEquals(expected, response.stdout)
        }
    }

    @Test
    fun canvasHelpIsGolden() = runTest {
        val expected = """
            |meridian canvas: Read and change this conversation's canvas (notes, checklists, cards, diagrams).
            |
            |  compose         Put notes, checklists, cards, text and labelled groups on the conversation canvas (or pass canvas...
            |  guide           Describe the canvas_compose format (letta.canvas.compose version 1): the kinds and their fields,...
            |  layout          Read a canvas's geometry without its full payloads (no canvas_id: this conversation's canvas).
            |  scene           Get a canvas's current scene (DrawBox JSON) and revision (no canvas_id: this conversation's canvas).
            |  apply-ops       Apply a sequence of canvas operations (no canvas_id: this conversation's canvas).
            |  replace-scene   Replace the whole drawing of a canvas (block-document notes are kept; no canvas_id: this conversa...
            |  list            List the canvases for a conversation, or every canvas you may read; the current one (this convers...
            |  create          Create a canvas; returns its canvas_id.
            |  preview         Render a proposed scene or ops without publishing, or the current published revision, using the m...
            |
            |Run `meridian canvas <verb> --help` for a verb's flags and input.
            |Exit codes: 0 ok, 2 refused input, 3 denied, 4 host unavailable. Errors print {"error": ...}.
            |
        """.trimMargin()
        assertEquals(expected, run("canvas", "--help").stdout)
        assertEquals(expected, run("help", "canvas").stdout)
    }

    @Test
    fun aVerbsHelpIsTheToolsOwnDescriptionAndFlags() = runTest {
        val help = run("canvas", "layout", "--help").stdout
        assertTrue(help.startsWith("meridian canvas layout [flags]\nRuns the canvas_get_layout tool.\n\n"), help)
        assertTrue(CanvasToolContract.getLayout.description in help)
        assertTrue("  --canvas-id <string> (or --canvas)  ${CanvasToolContract.CANVAS_ID_DESCRIPTION}" in help, help)
        assertTrue("  --limit <integer>  Rows to return, 1 to 500 (default 200)." in help, help)
        assertTrue(help.endsWith("Input schema: meridian schema canvas layout\n"), help)

        val compose = run("canvas", "compose", "--help").stdout
        assertTrue(CanvasToolContract.compose.description in compose)
        assertTrue("< input.json" in compose.lineSequence().first(), compose)
        assertTrue("  --dry-run" in compose, compose)

        val applyOps = run("help", "canvas", "apply-ops").stdout
        assertTrue("On stdin only (JSON): ops" in applyOps, applyOps)
        assertTrue("Required: ops" in applyOps, applyOps)
    }

    @Test
    fun schemasAreTheToolsInputSchemas() = runTest {
        CanvasToolContract.withPreview.forEach { definition ->
            val command = router.commands().first { it.toolName == definition.name && !it.hidden }
            assertEquals(definition.inputSchema.toString(), run("schema", *command.path.toTypedArray()).stdout, definition.name)
        }
        assertEquals(CanvasToolContract.compose.inputSchema.toString(), run("canvas", "schema", "compose").stdout)
    }

    @Test
    fun guidesServeTheContractTextAndTheComposeGuideTool() = runTest {
        assertEquals(CanvasSceneSchema.description.trimEnd() + "\n", run("guide", "scene").stdout)
        assertEquals(CanvasToolContract.applyOps.description.trimEnd() + "\n", run("guide", "ops").stdout)
        val composeGuide = run("guide", "compose")
        assertEquals(MeridianExit.OK, composeGuide.exitCode)
        assertEquals(run("canvas", "guide").stdout, composeGuide.stdout)
        assertEquals(run("canvas", "guide", "compose").stdout, composeGuide.stdout)
        assertTrue(run("guide").stdout.contains("  scene "))
    }

    @Test
    fun aHostWithoutCanvasListsNoCanvasOrGuide() = runTest {
        val help = MeridianTestHost(withCanvas = false).router().execute(MeridianRequest(listOf("--help"))).stdout
        assertTrue("  canvas " !in help, help)
        assertTrue("  guide " !in help, help)
        assertTrue("  agents " in help, help)
    }
}
