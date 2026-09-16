package com.letta.mobile.ui.canvas

import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0 verification test for Canvas Workspace:
 * Proves that official DrawBox sample diagrams (Build Cycle and Daily Loop) import
 * cleanly into DrawBoxController and round-trip to valid JSON ("elements") and SVG ("<svg").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasImportExportTest {

    private fun createController() = DrawBoxController(Reducer(UseCase()))

    private data class ExportResult(val json: String, val svg: String)

    private fun kotlinx.coroutines.test.TestScope.exportDiagram(controller: DrawBoxController): ExportResult {
        var exportedJson: String? = null
        var exportedSvg: String? = null
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.collect { event ->
                when (event) {
                    is Event.JsonExported -> exportedJson = event.json
                    is Event.SvgExported -> exportedSvg = event.svg
                    else -> Unit
                }
            }
        }
        runCurrent()

        controller.exportJson()
        controller.exportSvg()
        runCurrent()
        job.cancel()

        val validJson = requireNotNull(exportedJson) { "Exported JSON should not be null" }
        assertTrue(validJson.contains("\"elements\""), "Exported JSON must contain 'elements'")

        val validSvg = requireNotNull(exportedSvg) { "Exported SVG should not be null" }
        assertTrue(validSvg.contains("<svg", ignoreCase = true), "Exported SVG must contain '<svg'")

        return ExportResult(json = validJson, svg = validSvg)
    }

    @Test
    fun importBuildCycleSample_populatesElementsAndExports() = runTest {
        val controller = createController()
        val json = CanvasSamples.buildCycleJson

        controller.importPath(json)

        val elements = controller.state.value.elements
        assertTrue(elements.isNotEmpty(), "Build cycle elements should not be empty")
        assertEquals(15, elements.size, "Build cycle diagram should contain 15 elements")

        val exports = exportDiagram(controller)
        assertTrue(exports.json.contains("Build Cycle"), "Exported JSON must contain 'Build Cycle' title")
        assertTrue(exports.svg.contains("Build Cycle"), "Exported SVG must contain text in SVG")
    }

    @Test
    fun importDailyLoopSample_populatesElementsAndExports() = runTest {
        val controller = createController()
        val json = CanvasSamples.dailyLoopJson

        controller.importPath(json)

        val elements = controller.state.value.elements
        assertTrue(elements.isNotEmpty(), "Daily loop elements should not be empty")

        exportDiagram(controller)
    }

    @Test
    fun resetCanvas_clearsAllElements() {
        val controller = createController()
        controller.importPath(CanvasSamples.buildCycleJson)
        assertTrue(controller.state.value.elements.isNotEmpty())

        controller.reset()
        assertTrue(controller.state.value.elements.isEmpty(), "Reset should clear all canvas elements")
    }
}
