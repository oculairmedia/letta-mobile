@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import com.letta.mobile.ui.image.decodeImageBitmap
import kotlin.test.assertNotNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.controller.node.iroh.MessageListPageGuard
import com.letta.mobile.data.controller.node.iroh.MessageListWireProjection
import com.letta.mobile.data.controller.node.iroh.MessagePage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LocalReducedMotion
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Optional stored inputs supplement the portable, deterministic >1 MiB regression image. */
class ToolImagePipelineUiTest {
    @Test
    fun historyAndLiveToolImagesReachTheFoldedRun() {
        inputs().forEach { input ->
            val directory = Files.createTempDirectory("tool-image-pipeline").toFile()
            try {
                verifyInput(directory, input)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun verifyInput(directory: File, input: JsonObject) {
        val message = input.getValue("message").jsonObject
        val callId = message.getValue("toolCallId").jsonPrimitive.content
        val call = buildJsonObject {
            put("id", "call-row")
            put("role", "assistant")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "toolCall"); put("id", callId); put("name", "ViewImage")
                    put("arguments", buildJsonObject {})
                })
            })
        }
        val key = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("conversation:$CONVERSATION".encodeToByteArray())
        val storeDirectory = File(directory, "conversations/$key").apply { mkdirs() }
        File(storeDirectory, "conversation.json").writeText("""{"id":"$CONVERSATION","agent_id":"image-agent"}""")
        File(storeDirectory, "messages.jsonl").writeText("$call\n$input\n")
        val native = LocalBackendAdminStore(directory).listMessagesProjected(CONVERSATION, null, MessagePage(null, null, null, null))!!
        val projected = MessageListWireProjection.projectMessageList(native, CONVERSATION)
        val bounded = MessageListPageGuard.bound(projected)
        val rows = if (bounded is JsonArray) bounded else bounded.jsonObject.getValue("messages").jsonArray
        val history = rows.map { JSON.decodeFromJsonElement<LettaMessage>(it) }
        val empty = Timeline(CONVERSATION)
        val hydrated = TimelineHydrationReducer.reduce(CONVERSATION, history, empty, empty, emptyList()).timeline
        assertRendered(hydrated, message.getValue("id").jsonPrimitive.content + " history")
        val live = rows.flatMap { row ->
            val envelope = buildJsonObject { put("type", "stream_delta"); put("delta", row) }
            RuntimeEventServerFrameMapper.map(
                RuntimeEventPayload.RemoteStreamFrame("frame", null, null, envelope.toString()),
                RuntimeEventServerFrameMapper.Context("image-agent", CONVERSATION, "image-turn", RUN),
            ).map { requireNotNull(WsFrameMapper.toLettaMessage(it)) }
        }
        var timeline = empty
        var pending = persistentMapOf<String, com.letta.mobile.data.model.ToolReturnMessage>()
        live.forEach { frame ->
            val result = reduceStreamFrame(TimelineReducerInput(timeline, frame, pending))
            timeline = result.next
            pending = result.updatedPendingToolReturnsByCallId
        }
        assertRendered(timeline, message.getValue("id").jsonPrimitive.content + " live")
    }

    private fun assertRendered(timeline: Timeline, label: String) {
        val messages = timeline.events.mapNotNull { timelineEventToUiMessage(it) }.map { it.copy(runId = RUN) }
        val images = messages.flatMap { it.toolCalls.orEmpty() }.flatMap { it.generatedImageAttachments }
        assertEquals(1, images.size, "$label projected attachment")
        val expected = ImageIO.read(Base64.decode(images.single().base64).inputStream())
        val decoded = assertNotNull(decodeImageBitmap(Base64.decode(images.single().base64)), label)
        assertEquals(expected.width, decoded.width, label)
        assertEquals(expected.height, decoded.height, label)
        val pixels = decoded.toPixelMap()
        for (fraction in 1..3) {
            val x = expected.width * fraction / 4
            val y = expected.height * fraction / 4
            val actual = pixels[x, y].toArgb()
            val reference = expected.getRGB(x, y)
            for (shift in listOf(0, 8, 16)) {
                assertTrue(kotlin.math.abs(((actual shr shift) and 255) - ((reference shr shift) and 255)) <= 4, "$label pixel oracle")
            }
        }
        runDesktopComposeUiTest(width = 1000, height = 1600) {
            val item = ChatRenderItem.RunBlock(RUN, messages.map { it to GroupPosition.None })
            setContent {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    MaterialTheme {
                        Box(Modifier.width(600.dp)) { RenderRow(item, rowContext(), rowCallbacks()) }
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithContentDescription("Attached image", useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            val painted = onAllNodesWithContentDescription("Attached image", useUnmergedTree = true)[0].captureToImage()
            assertTrue(painted.width > 0 && painted.height > 0, "$label painted image")
        }
    }

    private fun inputs(): List<JsonObject> {
        val supplied = System.getenv("BXFQ6_FIXTURES")?.let(::File)
        if (supplied != null) {
            val stored = supplied.listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }
                .map { JSON.parseToJsonElement(it.readText()).jsonObject }
            val template = stored.first()
            return stored + supplied.listFiles()!!.filter { it.extension != "json" }.sortedBy { it.name }.map { file ->
                val message = template.getValue("message").jsonObject
                val mime = if (file.extension == "jpg") "image/jpeg" else "image/png"
                JsonObject(template + ("message" to JsonObject(message + ("content" to imageParts(file.readBytes(), mime)))))
            }
        }
        val random = Random(418027)
        val image = BufferedImage(832, 1216, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        assertTrue(bytes.size > MEBIBYTE, "regression must exercise a real large PNG")
        return listOf(buildJsonObject {
            put("message", buildJsonObject {
                put("id", "large-image"); put("role", "toolResult"); put("toolCallId", "image-call")
                put("toolName", "ViewImage"); put("content", imageParts(bytes, "image/png"))
            })
        })
    }

    private fun imageParts(bytes: ByteArray, mime: String) = buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", "Image result") })
        add(buildJsonObject { put("type", "image"); put("mimeType", mime); put("data", Base64.encode(bytes)) })
    }

    private companion object {
        const val CONVERSATION = "image-pipeline"
        const val RUN = "image-run"
        const val MEBIBYTE = 1024 * 1024
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
