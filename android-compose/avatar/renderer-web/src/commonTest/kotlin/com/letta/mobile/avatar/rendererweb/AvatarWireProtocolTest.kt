package com.letta.mobile.avatar.rendererweb

import com.letta.mobile.avatar.core.AvatarCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AvatarWireProtocolTest {
    private val allCommands: List<AvatarRendererCommand> = listOf(
        AvatarRendererCommand.LoadAvatar(
            url = "file:///a.vrm",
            format = "vrm1",
            requestId = "load-1",
            accessories = listOf(
                AvatarRendererCommand.WireAccessory("glasses", listOf("Glasses_L", "Glasses_R")),
            ),
        ),
        AvatarRendererCommand.Unload,
        AvatarRendererCommand.SetExpression(key = "happy", weight = 0.8f),
        AvatarRendererCommand.SetViseme(key = "aa", weight = 1f),
        AvatarRendererCommand.SetMouthOpen(value = 0.4f),
        AvatarRendererCommand.SetLookTarget(space = "world", x = 1f, y = 2f, z = 3f),
        AvatarRendererCommand.SetLookTarget(space = "screen", x = 0.5f, y = 0.25f),
        AvatarRendererCommand.ClearLookTarget,
        AvatarRendererCommand.PlayGesture(id = "wave", fadeSeconds = 0.2f),
        AvatarRendererCommand.PlayAnimation(id = "Idle", loop = true),
        AvatarRendererCommand.SetAccessoryEnabled(id = "glasses", enabled = false),
        AvatarRendererCommand.SetCameraFraming(framing = "bust"),
        AvatarRendererCommand.CaptureThumbnail(requestId = "thumb-1", width = 256, height = 256),
    )

    private val allEvents: List<AvatarRendererEvent> = listOf(
        AvatarRendererEvent.Ready(protocolVersion = 1),
        AvatarRendererEvent.AvatarLoaded(
            requestId = "load-1",
            capabilities = AvatarCapabilities(supportsHumanoid = true, supportsExpressions = true),
        ),
        AvatarRendererEvent.AvatarLoadFailed(requestId = "load-1", message = "404"),
        AvatarRendererEvent.RendererError(message = "shader compile failed"),
        AvatarRendererEvent.ThumbnailCaptured(requestId = "thumb-1", dataUrl = "data:image/png;base64,AAAA"),
        AvatarRendererEvent.ThumbnailFailed(requestId = "thumb-1", message = "no avatar"),
    )

    @Test
    fun everyCommandRoundTrips() {
        allCommands.forEach { command ->
            assertEquals(command, AvatarWireProtocol.decodeCommand(AvatarWireProtocol.encodeCommand(command)))
        }
    }

    @Test
    fun everyEventRoundTrips() {
        allEvents.forEach { event ->
            assertEquals(event, AvatarWireProtocol.decodeEvent(AvatarWireProtocol.encodeEvent(event)))
        }
    }

    @Test
    fun wireFormatUsesTypeDiscriminatorTheJsSideCanSwitchOn() {
        val encoded = AvatarWireProtocol.encodeCommand(
            AvatarRendererCommand.SetExpression(key = "happy", weight = 1f),
        )
        assertTrue("\"type\":\"setExpression\"" in encoded, encoded)
        assertTrue("\"key\":\"happy\"" in encoded, encoded)
    }

    @Test
    fun hostIgnoresUnknownEventFieldsFromNewerRenderers() {
        val event = AvatarWireProtocol.decodeEvent(
            """{"type":"ready","protocolVersion":1,"futureField":"x"}""",
        )
        assertEquals(AvatarRendererEvent.Ready(1), event)
    }

    @Test
    fun malformedMessagesThrowWireException() {
        assertFailsWith<AvatarWireException> { AvatarWireProtocol.decodeEvent("not json") }
        assertFailsWith<AvatarWireException> {
            AvatarWireProtocol.decodeEvent("""{"type":"unknownEvent"}""")
        }
        assertFailsWith<AvatarWireException> { AvatarWireProtocol.decodeCommand("{}") }
    }
}
