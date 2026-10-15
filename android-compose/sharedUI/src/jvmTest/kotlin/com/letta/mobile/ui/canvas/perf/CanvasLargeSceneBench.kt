package com.letta.mobile.ui.canvas.perf

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneState
import com.letta.mobile.data.canvas.CanvasSceneStateGuard
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasImageAssets
import com.letta.mobile.ui.canvas.CanvasWorkspaceSupport
import com.letta.mobile.ui.canvas.ExternalSyncParams
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.DrawBoxRenderStats
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.topmostHit
import io.ak1.drawbox.domain.model.translate
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Measures what a large board costs, stage by stage, for the canvas-performance epic. Opt-in:
 * it prints and writes its numbers only with `CANVAS_BENCH=1` in the environment (sizes from
 * `CANVAS_BENCH_SIZES`, default `500,2000,10000`), so the ordinary test run is not slowed.
 *
 * ```
 * CANVAS_BENCH=1 ./gradlew --no-daemon :sharedUI:jvmTest --tests '*CanvasLargeSceneBench*'
 * ```
 * Results: `sharedUI/build/canvas-bench/results.txt`.
 */
class CanvasLargeSceneBench {
    private val out = StringBuilder()

    private fun log(line: String) {
        println("CANVAS-BENCH $line")
        out.appendLine(line)
    }

    @Test
    fun bench() {
        if (System.getenv("CANVAS_BENCH") != "1") return
        val sizes = (System.getenv("CANVAS_BENCH_SIZES") ?: "500,2000,10000").split(',').map { it.trim().toInt() }
        val parts = (System.getenv("CANVAS_BENCH_PARTS") ?: "data,hit,render,session").split(',').toSet()
        for (n in sizes) runSize(n, parts)
        File("build/canvas-bench").apply { mkdirs() }.resolve("results.txt").writeText(out.toString())
    }

    /** The stages named in [parts] on a board of [n] elements. */
    private fun runSize(n: Int, parts: Set<String>) {
        log("=== N=$n ===")
        val payload = CanvasLargeSceneFixtures.scene(n)
        val stages = linkedMapOf<String, () -> Unit>(
            "data" to { dataPath(payload) },
            "hit" to { hitTesting(payload) },
            "render" to { profiled { render(payload) } },
            // The notebook store is slow enough on big boards that 2,000 elements is the useful top.
            "session" to { if (n <= 2000) profiled { sessionOps(payload) } },
        )
        stages.filterKeys { it in parts }.values.forEach { it() }
    }

    /** Runs [block] under a JFR recording written to `CANVAS_BENCH_JFR`, when that is set. */
    private fun profiled(block: () -> Unit) {
        val path = System.getenv("CANVAS_BENCH_JFR") ?: return block()
        jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).use { recording ->
            recording.start()
            block()
            recording.stop()
            recording.dump(java.nio.file.Path.of(path))
        }
    }

    // ---------------------------------------------------------------- data path

    private fun dataPath(payload: PayLoad) {
        val json = DrawingSerializer.serialize(payload)
        log("scene.chars=${json.length} elements=${payload.elements.size}")
        log("serialize.ms=${median { DrawingSerializer.serialize(payload) }}")
        log("deserialize.ms=${median { DrawingSerializer.deserialize(json) }}")
        log("stripMetadata.ms=${median { CanvasOpProjector.stripMetadataForDrawBox(json) }}")
        log("stateViolations.ms=${median { CanvasSceneState.violations(json) }}")
        val other = DrawingSerializer.serialize(payload.copy(elements = payload.elements.dropLast(1)))
        log("drawingsEqual.ms=${median { CanvasOpProjector.drawingsEqual(json, other) }}")
        log("sceneGuard.drawable.ms=${median { CanvasSceneStateGuard(null).drawable(json) }}")
        // The whole off-main work of one sync.imported, as CanvasBoardSync does it.
        var rev = 1L
        log(
            "syncImport.offMain.ms=" + median {
                val doc = CanvasDocument(id = CanvasId("bench"), title = "b", revision = ++rev, sceneJson = json, updatedAtEpochMs = 0L)
                val params = ExternalSyncParams(doc, rev - 1, lastExportedJson = null, lastDrawing = other)
                val evaluated = CanvasWorkspaceSupport.evaluateExternalDocSync(params)
                if (evaluated?.shouldImport == true) CanvasImageAssets.parse(CanvasSceneStateGuard(null).drawable(json), null)
            },
        )
        // What autosave does after every import or edit: serialize + strip twice. On the main thread before
        // letta-mobile-24zur.2, on Dispatchers.Default after (and skipped for a drawing just imported).
        log(
            "autosave.serializeAndStrip.ms=" + median {
                val exported = DrawingSerializer.serialize(payload)
                CanvasOpProjector.stripMetadataForDrawBox(exported)
                CanvasOpProjector.stripMetadataForDrawBox(exported)
            },
        )
    }

    // ---------------------------------------------------------------- hit testing

    private fun hitTesting(payload: PayLoad) {
        val elements = payload.elements
        val far = Offset(-500f, -500f) // a miss walks every element
        log("hitTest.miss.ms=${median(reps = 20) { topmostHit(elements, far) }}")
        log("bounds.all.ms=${median(reps = 20) { elements.forEach { it.bounds() } }}")
    }

    // ---------------------------------------------------------------- render

    private fun render(payload: PayLoad) {
        val controller = DrawBoxController(Reducer(UseCase()), State())
        controller.importPath(payload)
        val scene = ImageComposeScene(width = 1280, height = 800, density = Density(1f)) {
            val s by controller.state.collectAsState()
            DrawBox(state = s, onIntent = controller::onIntent, showGrid = true)
        }
        var clock = 0L
        fun frame(): Double {
            clock += 16_000_000L
            val start = System.nanoTime()
            scene.render(clock)
            return (System.nanoTime() - start) / 1e6
        }
        // One scenario: [frames] frames each preceded by [step]; median ms and elements drawn per frame.
        fun scenario(name: String, frames: Int = 40, step: () -> Unit) {
            DrawBoxRenderStats.reset()
            val times = List(frames) { step(); frame() }
            val drawn = DrawBoxRenderStats.elementsDrawn / frames
            val records = DrawBoxRenderStats.layerRecords
            log("render.$name.ms=${fmt(medianOf(times))} drawnPerFrame=$drawn layerRecords=$records/$frames")
        }
        try {
            val first = frame()
            repeat(5) { frame() }
            Thread.sleep(300) // image decodes land
            repeat(3) { frame() }
            log("render.first.ms=${fmt(first)}")
            scenario("idle") { }
            scenario("pan") { controller.onIntent(Intent.PanBy(Offset(-7f, -3f))) }
            scenario("zoom") { controller.onIntent(Intent.ZoomBy(1.005f, Offset(640f, 400f))) }
            val target = payload.elements.first { it is Element.Shape }
            var moved = target
            scenario("dragOne") {
                moved = moved.translate(Offset(2f, 1f))
                controller.onIntent(Intent.UpdateElement(moved))
            }
            // An external import as an agent's op makes it: one element moved, every element a new
            // instance (as parsing makes it).
            var nudge = 0f
            scenario("afterImport", frames = 10) {
                nudge += 3f
                val fresh = payload.elements.map { it.freshCopy() }.toMutableList()
                val i = fresh.indexOfFirst { it is Element.Shape }
                fresh[i] = fresh[i].translate(Offset(nudge, 0f))
                controller.importExternal(payload.copy(elements = fresh))
            }
            log("render.importApply.mainThread.ms=${median(reps = 8) { controller.importExternal(payload.copy(elements = payload.elements.map { it.freshCopy() })) }}")
        } finally {
            scene.close()
        }
    }

    private fun Element.freshCopy(): Element = when (this) {
        is Element.Path -> copy(samples = samples.map { it.copy() })
        is Element.Shape -> copy(points = points.toList())
        is Element.Text -> copy()
        is Element.Image -> copy(bytes = bytes.copyOf(), points = points.toList())
    }

    // ---------------------------------------------------------------- session ops

    private fun sessionOps(payload: PayLoad) {
        val json = DrawingSerializer.serialize(payload)
        val shapes = payload.elements.filterIsInstance<Element.Shape>().take(OPS)
        val ops = shapes.mapIndexed { i, shape -> updateOp(shape.translate(Offset(5f, 5f)), i) }
        runBlocking {
            val memory = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(initialSceneJson = json))
            val inMemory = timeEach(ops) { memory.applyRemote(it) }
            log("remoteOp.inMemoryStore.ms=${fmt(medianOf(inMemory))}")

            val dir = Files.createTempDirectory("canvas-bench-")
            NotebookLocalStore(dir, "bench-peer").use { notebooks ->
                val session = CanvasSession.create(NotebookCanvasDocumentStore(notebooks), CanvasCreateOptions(initialSceneJson = json))
                val before = dirBytes(dir)
                val notebook = timeEach(ops) { session.applyRemote(it) }
                val after = dirBytes(dir)
                log("remoteOp.notebookStore.ms=${fmt(medianOf(notebook))} p90=${fmt(notebook.sorted()[notebook.size * 9 / 10])}")
                log("notebook.bytes.initial=$before growthPerOp=${(after - before) / ops.size}")
                val diffJson = DrawingSerializer.serialize(payload.copy(elements = payload.elements.map { if (it.id == shapes.first().id) it.translate(Offset(1f, 0f)) else it }))
                log("localScene.diffApply.ms=${fmt(medianOf(List(5) { val t = System.nanoTime(); session.applyLocalScene(if (it % 2 == 0) diffJson else json); (System.nanoTime() - t) / 1e6 }))}")
            }
            dir.toFile().deleteRecursively()
        }
    }

    private fun updateOp(element: Element, i: Int): CanvasOp.UpdateElementOp {
        val one = DrawingSerializer.serialize(PayLoad(bgColor = androidx.compose.ui.graphics.Color.White, elements = listOf(element)))
        val elementJson = Json.parseToJsonElement(one).jsonObject["elements"]!!.jsonArray[0].toString()
        return CanvasOp.UpdateElementOp(opId = "bench-$i", actorId = "local_user", lamport = 10_000L + i, elementId = element.id, elementJson = elementJson)
    }

    private suspend fun <T> timeEach(items: List<T>, block: suspend (T) -> Unit): List<Double> = items.map {
        val t = System.nanoTime()
        block(it)
        (System.nanoTime() - t) / 1e6
    }

    private fun dirBytes(dir: Path): Long = Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum() }

    // ---------------------------------------------------------------- timing

    private fun median(reps: Int = 7, block: () -> Unit): String {
        repeat(2) { block() }
        return fmt(medianOf(List(reps) { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }))
    }

    private fun medianOf(samples: List<Double>): Double = samples.sorted()[samples.size / 2]

    private fun fmt(ms: Double): String = "%.2f".format(ms)

    private companion object {
        const val OPS = 40
    }
}
