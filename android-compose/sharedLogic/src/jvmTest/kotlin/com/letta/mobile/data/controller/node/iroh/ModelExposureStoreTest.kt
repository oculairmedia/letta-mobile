package com.letta.mobile.data.controller.node.iroh

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-w4q4p: the wrapper-owned model exposure file. */
class ModelExposureStoreTest {
    private val dir: File = Files.createTempDirectory("model-exposure").toFile()
    private val file = File(dir, FileModelExposureStore.FILE_NAME)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun everyHandleIsExposedByDefault() {
        val store = FileModelExposureStore(file)

        assertTrue(store.isExposed("openai/gpt-sol"))
        assertEquals(emptyMap(), store.decisions())
        assertFalse(file.exists(), "reading must not create the file")
    }

    @Test
    fun hiddenDecisionsSurviveANewStoreInstance() {
        FileModelExposureStore(file).apply(mapOf("openai/gpt-sol" to false, "lmstudio/m3" to false))

        val reopened = FileModelExposureStore(file)

        assertFalse(reopened.isExposed("openai/gpt-sol"))
        assertFalse(reopened.isExposed("lmstudio/m3"))
        assertTrue(reopened.isExposed("anthropic/claude"))
    }

    @Test
    fun reExposingAHandleRemovesItFromTheFile() {
        val store = FileModelExposureStore(file)
        store.apply(mapOf("openai/gpt-sol" to false))

        store.apply(mapOf("openai/gpt-sol" to true))

        assertEquals(emptyMap(), FileModelExposureStore(file).decisions())
        assertFalse(file.readText().contains("gpt-sol"))
    }

    @Test
    fun theDefaultPathSitsBesideHostCanvases() {
        val opsDir = File(dir, "canvas-relay/topics").path

        assertEquals(File(dir, "canvas-relay/model-exposure.json").absolutePath, FileModelExposureStore.resolvePath(null, opsDir))
        assertEquals("/x/override.json", FileModelExposureStore.resolvePath("/x/override.json", opsDir))
    }

    @Test
    fun aCorruptFileHidesNothing() {
        file.writeText("{not json")

        assertTrue(FileModelExposureStore(file).isExposed("openai/gpt-sol"))
    }

    @Test
    fun theFileIsVersionedAndLeavesNoTempFileBehind() {
        FileModelExposureStore(file).apply(mapOf("b/y" to false, "a/x" to false))

        val text = file.readText()
        assertTrue(text.contains("\"version\": 1"), text)
        assertTrue(text.indexOf("a/x") < text.indexOf("b/y"), "handles are written sorted")
        assertEquals(listOf(FileModelExposureStore.FILE_NAME), dir.list()!!.toList())
    }
}
