package com.letta.mobile.avatar.rendererweb

import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.avatar.core.AvatarCapabilities
import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarModel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The full behavior stack short of pixels: AvatarDirector policy →
 * WebAvatarRuntime gating/bookkeeping → wire protocol commands. What this
 * asserts is exactly what the browser (or any future native renderer)
 * receives.
 */
class DirectorOverWireTest {
    private class RecordingTransport : AvatarRendererTransport {
        val sent = mutableListOf<AvatarRendererCommand>()
        private var handler: (String) -> Unit = {}
        override fun send(message: String) {
            sent += AvatarWireProtocol.decodeCommand(message)
        }
        override fun setMessageHandler(handler: (String) -> Unit) {
            this.handler = handler
        }
        override fun close() {}
        fun emit(event: AvatarRendererEvent) = handler(AvatarWireProtocol.encodeEvent(event))
    }

    @Test
    fun directorBehaviorReachesTheWireAsProtocolCommands() = runTest {
        val transport = RecordingTransport()
        val runtime = WebAvatarRuntime(transport)
        transport.emit(AvatarRendererEvent.Ready(AvatarWireProtocol.VERSION))

        val model = AvatarModel(
            id = "buddy",
            displayName = "Buddy",
            uri = "http://127.0.0.1/asset/buddy.vrm",
            format = AvatarFormat.VRM_1,
        )
        val job = launch { runtime.load(model) }
        runCurrent()
        transport.emit(
            AvatarRendererEvent.AvatarLoaded(
                requestId = (transport.sent.single() as AvatarRendererCommand.LoadAvatar).requestId,
                capabilities = AvatarCapabilities(
                    supportsHumanoid = true,
                    supportsExpressions = true,
                    supportsVisemes = true,
                    supportsLookAt = true,
                ),
            ),
        )
        runCurrent()
        job.join()

        // Drive a realistic minute of agent life at 30fps-ish steps.
        val director = AvatarDirector(runtime, Random(3))
        director.setActivity(AvatarActivity.THINKING)
        repeat(30) { director.tick(0.033f) }
        director.setActivity(AvatarActivity.SPEAKING)
        director.setSpeechLevel(0.8f)
        repeat(30) { director.tick(0.033f) }
        director.setLookTarget(AvatarLookTarget.Screen(0.5f, 0.4f))
        director.setActivity(AvatarActivity.IDLE)
        repeat(200) { director.tick(0.033f) } // long enough to blink

        val wire = transport.sent.drop(1) // drop LoadAvatar
        assertTrue(
            wire.any { it is AvatarRendererCommand.SetExpression && it.key == "relaxed" && it.weight > 0f },
            "thinking base expression should reach the wire",
        )
        assertTrue(
            wire.any { it is AvatarRendererCommand.SetMouthOpen && it.value > 0.5f },
            "speech level should drive mouth-open commands",
        )
        assertTrue(
            wire.any { it is AvatarRendererCommand.SetLookTarget && it.space == "screen" },
            "look target should pass through",
        )
        assertTrue(
            wire.any { it is AvatarRendererCommand.SetExpression && it.key == "blink" && it.weight == 1f },
            "idle blinking should reach the wire",
        )
    }
}
