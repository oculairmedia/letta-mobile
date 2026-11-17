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
import kotlin.test.assertContentEquals
import org.junit.Assume.assumeTrue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
    fun explicitTooLargeMarkerRendersItsReasonThroughHistory() {
        val wire = JSON.parseToJsonElement("""[
            {"id":"marker-call","message_type":"tool_call_message","tool_call":{"name":"ViewImage","arguments":"{}","tool_call_id":"marker"}},
            {"id":"marker-return","message_type":"tool_return_message","tool_call_id":"marker","tool_return":[{"type":"image","unavailable_reason":"too_large"}]}
        ]""").jsonArray
        val history = wire.map { JSON.decodeFromJsonElement<LettaMessage>(it) }
        val empty = Timeline(CONVERSATION)
        val timeline = TimelineHydrationReducer.reduce(CONVERSATION, history, empty, empty, emptyList()).timeline
        val messages = timeline.events.mapNotNull { timelineEventToUiMessage(it) }.map { it.copy(runId = RUN) }
        runDesktopComposeUiTest(width = 1000, height = 1600) {
            setContent {
                MaterialTheme {
                    RenderRow(ChatRenderItem.RunBlock(RUN, messages.map { it to GroupPosition.None }), rowContext(), rowCallbacks())
                }
            }
            onNodeWithText("Image unavailable: image is too large").assertExists()
        }
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test
    fun supportedUrlReachesTheHostLoaderAndPaintsPixels() {
        val url = "https://example.com/image.png"
        val requested = java.util.concurrent.atomic.AtomicReference<String>()
        val previous = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        val loader = coil3.ImageLoader.Builder(coil3.PlatformContext.INSTANCE).components {
            add(coil3.intercept.Interceptor { chain ->
                requested.set(chain.request.data.toString())
                require(chain.request.data == url) { "unexpected image source" }
                coil3.request.SuccessResult(
                    image = coil3.ColorImage(color = URL_COLOR, width = URL_IMAGE_SIZE, height = URL_IMAGE_SIZE),
                    request = chain.request,
                )
            })
        }.build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        try {
            val parsed = com.letta.mobile.data.model.ToolReturnMessage(
                id = "url-result", toolReturnRaw = JSON.parseToJsonElement(
                    """{"type":"image","source":{"type":"url","url":"$url"}}""",
                ),
            ).attachments.single()
            runDesktopComposeUiTest(width = 1000, height = 1600) {
                setContent {
                    MaterialTheme {
                        ChatAttachmentImage(com.letta.mobile.data.model.UiImageAttachment(
                            parsed.base64, parsed.mediaType, url = parsed.url,
                        ))
                    }
                }
                waitUntil(timeoutMillis = 10_000) { requested.get() == url }
                waitForIdle()
                val pixels = onNodeWithContentDescription("Attached image").captureToImage().toPixelMap()
                assertEquals(URL_COLOR, pixels[pixels.width / 2, pixels.height / 2].toArgb(), "loader image must be painted")
                onNodeWithText("Image unavailable: image data is missing").assertDoesNotExist()
            }
        } finally {
            coil3.SingletonImageLoader.setUnsafe(previous)
            loader.shutdown()
        }
    }

    @Test fun generatedLargePng() {
        val directory = Files.createTempDirectory("tool-image-generated").toFile()
        try { verifyInput(directory, generatedInput()) } finally { directory.deleteRecursively() }
    }

    @Test fun failingPng() = verifyNamed("failing-png-toolresult.json")
    @Test fun shownJpeg() = verifyNamed("shown-jpeg-toolresult.json")
    @Test fun shownSmallPng() = verifyNamed("shown-small-png-toolresult.json")
    @Test fun strippedBigPng() = verifyNamed("v1-big-png-stripped.png")
    @Test fun bigJpeg() = verifyNamed("v2-big-jpeg-q100.jpg")
    @Test fun smallPngWithPrompt() = verifyNamed("v3-small-png-with-prompt.png")

    private fun verifyNamed(name: String) {
        assumeTrue("Stored evidence is opt-in; the generated regression always runs", System.getenv("BXFQ6_FIXTURES") != null)
        val input = inputs().first { it.first == name }.second
        val directory = Files.createTempDirectory("tool-image-pipeline").toFile()
        try {
            verifyInput(directory, input)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun verifyInput(directory: File, input: JsonObject) {
        val message = input.getValue("message").jsonObject
        val imagePart = message.getValue("content").jsonArray.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "image"
        }.jsonObject
        val expectedBytes = Base64.decode(imagePart.getValue("data").jsonPrimitive.content)
        val native = projectStored(directory, input)
        val projected = MessageListWireProjection.projectMessageList(native, CONVERSATION)
        val bounded = MessageListPageGuard.bound(projected)
        val rows = if (bounded is JsonArray) bounded else bounded.jsonObject.getValue("messages").jsonArray
        assertEquals(2, rows.size, "page must retain the invoking call")
        val history = rows.map { JSON.decodeFromJsonElement<LettaMessage>(it) }
        assertEquals(1, history.filterIsInstance<com.letta.mobile.data.model.ToolReturnMessage>().single().attachments.size,
            "wire must retain the image")
        val empty = Timeline(CONVERSATION)
        val hydrated = TimelineHydrationReducer.reduce(CONVERSATION, history, empty, empty, emptyList()).timeline
        assertRendered(hydrated, expectedBytes, message.getValue("id").jsonPrimitive.content + " history")
        val live = (projected as JsonArray).flatMap { row ->
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
        assertRendered(timeline, expectedBytes, message.getValue("id").jsonPrimitive.content + " live")
    }

    private fun projectStored(directory: File, input: JsonObject): JsonArray {
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
        return LocalBackendAdminStore(directory).listMessagesProjected(CONVERSATION, null, MessagePage(null, null, null, null))!!
    }

    @Test
    fun missingStoredPayloadRemainsAnImage() {
        val input = generatedInput()
        val message = input.getValue("message").jsonObject
        val missing = JsonObject(input + ("message" to JsonObject(message + ("content" to buildJsonArray {
            add(buildJsonObject { put("type", "image"); put("mimeType", "image/png") })
        }))))
        val directory = Files.createTempDirectory("tool-image-missing").toFile()
        try {
            val wire = MessageListWireProjection.projectMessageList(projectStored(directory, missing), CONVERSATION) as JsonArray
            val returned = wire.map { JSON.decodeFromJsonElement<LettaMessage>(it) }
                .filterIsInstance<com.letta.mobile.data.model.ToolReturnMessage>().single()
            assertEquals(1, returned.attachments.size, "missing payload must retain a failure placeholder")
            assertEquals("", returned.attachments.single().base64)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertRendered(timeline: Timeline, expectedBytes: ByteArray, label: String) {
        val messages = timeline.events.mapNotNull { timelineEventToUiMessage(it) }.map { it.copy(runId = RUN) }
        val images = messages.flatMap { it.toolCalls.orEmpty() }.flatMap { it.generatedImageAttachments }
        assertEquals(1, images.size, "$label projected attachment; " +
            timeline.events.joinToString { event ->
                when (event) {
                    is com.letta.mobile.data.timeline.TimelineEvent.Confirmed ->
                        "${event.messageType} calls=${event.toolCalls.map { it.effectiveId }} attachments=${event.attachments.size}"
                    is com.letta.mobile.data.timeline.TimelineEvent.Local -> "local"
                }
            })
        assertContentEquals(expectedBytes, Base64.decode(images.single().base64), "$label preserved image bytes")
        val expected = ImageIO.read(expectedBytes.inputStream())
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
            val renderedPixels = painted.toPixelMap()
            val colors = (1..PAINT_SAMPLES).flatMap { x ->
                (1..PAINT_SAMPLES).map { y ->
                    renderedPixels[x * (painted.width - 1) / PAINT_SAMPLES, y * (painted.height - 1) / PAINT_SAMPLES].toArgb()
                }
            }.toSet()
            assertTrue(colors.size > MIN_PAINTED_COLORS, "$label must paint image content")
        }
    }

    private fun inputs(): List<Pair<String, JsonObject>> {
        val supplied = System.getenv("BXFQ6_FIXTURES")?.let(::File)
        if (supplied != null) {
            val stored = supplied.listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }
                .map { it.name to JSON.parseToJsonElement(it.readText()).jsonObject }
            val template = stored.first().second
            return stored + supplied.listFiles()!!.filter { it.extension != "json" }.sortedBy { it.name }.map { file ->
                val message = template.getValue("message").jsonObject
                val mime = if (file.extension == "jpg") "image/jpeg" else "image/png"
                file.name to JsonObject(template + ("message" to JsonObject(message + ("content" to imageParts(file.readBytes(), mime)))))
            }
        }
        return emptyList()
    }

    private fun generatedInput(): JsonObject {
        val random = Random(418027)
        val image = BufferedImage(832, 1216, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        assertTrue(bytes.size > MEBIBYTE, "regression must exercise a real large PNG")
        return buildJsonObject {
            put("type", "message")
            put("message", buildJsonObject {
                put("id", "large-image"); put("role", "toolResult"); put("toolCallId", "image-call")
                put("toolName", "ViewImage"); put("content", imageParts(bytes, "image/png"))
            })
        }
    }

    private fun imageParts(bytes: ByteArray, mime: String) = buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", "Image result") })
        add(buildJsonObject { put("type", "image"); put("mimeType", mime); put("data", Base64.encode(bytes)) })
    }

    private companion object {
        const val CONVERSATION = "image-pipeline"
        const val RUN = "image-run"
        const val URL_COLOR = -16711936
        const val URL_IMAGE_SIZE = 16
        const val PAINT_SAMPLES = 16
        const val MIN_PAINTED_COLORS = 8
        const val MEBIBYTE = 1024 * 1024
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
