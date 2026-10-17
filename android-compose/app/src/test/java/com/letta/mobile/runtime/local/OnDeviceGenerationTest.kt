package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogEntry
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDefaultConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-by4vm.121: the guard every on-device generation streams through. */
class OnDeviceGenerationTest {
    @Test
    fun `output stops at the token cap`() {
        val generation = OnDeviceGeneration(OnDeviceGenerationLimits(maxOutputTokens = 5))
        val accepted = (0 until 20).takeWhile { generation.accept("w$it ") }

        assertEquals(4, accepted.size)
        assertEquals(OnDeviceStopReason.MaxTokens, generation.stopReason)
        assertFalse(generation.accept("more"))
    }

    @Test
    fun `big chunks count by characters`() {
        val generation = OnDeviceGeneration(OnDeviceGenerationLimits(maxOutputTokens = 10))

        assertFalse(generation.accept("x".repeat(41)))
        assertEquals(OnDeviceStopReason.MaxTokens, generation.stopReason)
    }

    @Test
    fun `a transcript role marker ends the reply where it starts`() {
        val generation = OnDeviceGeneration()

        assertTrue(generation.accept("Sure, here it is."))
        assertFalse(generation.accept("\nuser: and then"))
        assertEquals("Sure, here it is.", generation.text)
        assertEquals(OnDeviceStopReason.StopSequence, generation.stopReason)
    }

    @Test
    fun `gemma end of turn marker split across chunks still stops`() {
        val generation = OnDeviceGeneration()

        assertTrue(generation.accept("Done.<end_of"))
        assertFalse(generation.accept("_turn>junk"))
        assertEquals("Done.", generation.text)
    }

    @Test
    fun `a word loop is cut to the text before it plus one copy`() {
        val generation = OnDeviceGeneration()
        generation.accept("I think ")
        var chunks = 0
        while (generation.accept("so ") && chunks < 500) chunks++

        assertEquals(OnDeviceStopReason.Repetition, generation.stopReason)
        assertTrue(chunks < 100)
        assertEquals("I think so", generation.text)
    }

    @Test
    fun `a repeated paragraph is a loop`() {
        val paragraph = "The weather today is sunny with a light breeze from the west. "
        val generation = OnDeviceGeneration()
        var copies = 0
        while (generation.accept(paragraph) && copies < 50) copies++

        assertEquals(OnDeviceStopReason.Repetition, generation.stopReason)
        assertTrue(copies <= 4)
        assertEquals(paragraph, generation.text)
    }

    @Test
    fun `ordinary prose and short repeats are not loops`() {
        assertNull(repetitionCutIndex("ha ha ha, that is funny. "))
        assertNull(repetitionCutIndex("Line one.\nLine two.\nLine three.\n".repeat(2)))
        val words = listOf("the", "quick", "brown", "fox", "jumps", "over", "a", "lazy", "dog", "today")
        val random = java.util.Random(1)
        val prose = (0 until 2_000).joinToString(" ") { words[random.nextInt(words.size)] }
        val generation = OnDeviceGeneration(OnDeviceGenerationLimits(maxOutputTokens = 100_000))
        prose.chunked(5).forEach { assertTrue(generation.accept(it)) }
    }

    @Test
    fun `cancel runs the engine's native cancel once and stops accepting`() {
        val generation = OnDeviceGeneration()
        var cancels = 0
        generation.onCancel { cancels++ }

        assertTrue(generation.cancel(OnDeviceStopReason.ClientDisconnected))
        assertFalse(generation.cancel(OnDeviceStopReason.Timeout))
        assertEquals(1, cancels)
        assertEquals(OnDeviceStopReason.ClientDisconnected, generation.stopReason)
        assertFalse(generation.accept("late"))
    }

    @Test
    fun `a cancel before the engine registers runs on registration`() {
        val generation = OnDeviceGeneration()
        generation.cancel(OnDeviceStopReason.ClientDisconnected)
        var cancels = 0
        generation.onCancel { cancels++ }

        assertEquals(1, cancels)
    }

    @Test
    fun `a cancel after the engine returned is a no op`() {
        val generation = OnDeviceGeneration()
        generation.accept("all done")
        generation.finish()

        assertFalse(generation.cancel(OnDeviceStopReason.ClientDisconnected))
        assertNull(generation.stopReason)
    }

    @Test
    fun `request max tokens lowers the cap but never raises it past the default or the engine context`() {
        val selection = selection(maxTokens = 4096)
        fun cap(body: String) = OnDeviceGenerationLimits.forRequest(Json.parseToJsonElement(body).jsonObject, selection).maxOutputTokens

        assertEquals(1_024, cap("""{}"""))
        assertEquals(200, cap("""{"max_tokens":200}"""))
        assertEquals(300, cap("""{"max_completion_tokens":300,"max_tokens":200}"""))
        assertEquals(1_024, cap("""{"max_tokens":32000}"""))
        val small = OnDeviceGenerationLimits.forRequest(Json.parseToJsonElement("{}").jsonObject, selection(maxTokens = 512))
        assertEquals(512, small.maxOutputTokens)
    }

    @Test
    fun `catalog capability matches by handle or model file`() {
        val entries = listOf(entry("google/gemma-3n-E2B-it-litert-lm", "gemma-3n-E2B-it-int4.litertlm", vision = true))

        assertEquals(true, catalogCapability(entries, selection(handle = "google/gemma-3n-E2B-it-litert-lm")).supportsImages)
        assertEquals(true, catalogCapability(entries, selection(path = "/x/models/gemma-3n-E2B-it-int4.litertlm")).supportsImages)
        assertNull(catalogCapability(entries, selection(handle = "local/imported", path = "/x/other.litertlm")).supportsImages)
        assertEquals(
            false,
            catalogCapability(listOf(entry("a/text", "text.litertlm", vision = false)), selection(handle = "a/text")).supportsImages,
        )
    }

    @Test
    fun `engine init tries the request, then vision on CPU, then text only`() {
        assertEquals(
            listOf(
                LiteRtLmInitAttempt("GPU", true),
                LiteRtLmInitAttempt("CPU", true),
                LiteRtLmInitAttempt("GPU", false),
                LiteRtLmInitAttempt("CPU", false),
            ),
            liteRtLmInitAttempts(LiteRtLmInitAttempt("GPU", vision = true)),
        )
        assertEquals(listOf(LiteRtLmInitAttempt("CPU", false)), liteRtLmInitAttempts(LiteRtLmInitAttempt("CPU", vision = false)))
    }

    private fun selection(
        maxTokens: Int = 4096,
        handle: String = "local/gemma",
        path: String? = "/data/model.litertlm",
    ) = EmbeddedLettaCodeModelSelection(
        modelHandle = handle,
        modelPath = path,
        runtime = "litert-lm",
        accelerator = "gpu",
        maxTokens = maxTokens,
    )

    private fun entry(id: String, file: String, vision: Boolean) = EmbeddedModelCatalogEntry(
        name = id,
        modelId = id,
        modelFile = file,
        sizeInBytes = 1,
        estimatedPeakMemoryInBytes = 1,
        defaultConfig = EmbeddedModelDefaultConfig(maxTokens = 4096, accelerators = listOf("gpu", "cpu")),
        taskTypes = listOf("chat"),
        llmSupportImage = vision,
    )
}
