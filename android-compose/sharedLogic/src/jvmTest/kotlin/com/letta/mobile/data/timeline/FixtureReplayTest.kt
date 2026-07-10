package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FixtureReplayTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `replay all jsonl fixtures in corpus`() {
        val resourceDir = File("src/jvmTest/resources/timeline-fixtures")
        if (!resourceDir.exists() || !resourceDir.isDirectory) {
            println("Skipping fixture test, dir not found: ${resourceDir.absolutePath}")
            return
        }

        val fixtureFiles = resourceDir.listFiles { _, name -> name.endsWith(".jsonl") }
        if (fixtureFiles.isNullOrEmpty()) {
            println("No fixtures found in ${resourceDir.absolutePath}")
            return
        }

        for (file in fixtureFiles) {
            runFixture(file)
        }
    }

    internal fun runFixture(file: File) {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = json.decodeFromString(JsonObject.serializer(), lines.first())
        val name = header["name"]?.jsonPrimitive?.content ?: file.name
        val expectedRowCount = header["expected_final_row_count"]?.jsonPrimitive?.int ?: 0
        val expectedText = header["expected_assistant_text"]?.jsonPrimitive?.content ?: ""

        val rawFrames = lines.drop(1)

        var tl = Timeline(conversationId = "conv-fixture")
        for (raw in rawFrames) {
            val msg = json.decodeFromString(LettaMessage.serializer(), raw)
            tl = reduceStreamFrame(
                TimelineReducerInput(prev = tl, frame = msg, pendingToolReturnsByCallId = kotlinx.collections.immutable.persistentMapOf())
            ).next
        }

        val assistantRows = tl.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.ASSISTANT }

        assertEquals(expectedRowCount, assistantRows.size, "[$name] expected $expectedRowCount assistant row(s)")
        if (expectedRowCount > 0) {
            val actual = assistantRows.single().content
            assertEquals(expectedText, actual, "[$name] assistant text mismatch")
        }
    }
}
