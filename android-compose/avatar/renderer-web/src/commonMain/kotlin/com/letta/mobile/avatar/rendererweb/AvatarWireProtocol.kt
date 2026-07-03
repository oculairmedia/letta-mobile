package com.letta.mobile.avatar.rendererweb

import com.letta.mobile.avatar.core.AvatarCapabilities
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned wire protocol between an [com.letta.mobile.avatar.core.AvatarRuntime]
 * host and a remote renderer (the bundled three-vrm web frontend today; any
 * future out-of-process renderer speaks the same messages).
 *
 * Design rules:
 * - JSON with a `type` discriminator, so the JS side needs no codegen.
 * - Expression/viseme keys travel as the normalized manifest strings — the
 *   same key space as `avatar.manifest.json`.
 * - Forward-tolerant on the host side (unknown event fields ignored); the
 *   renderer reports its protocol version in [AvatarRendererEvent.Ready] and
 *   the host refuses mismatched MAJORs.
 *
 * This protocol doubles as the renderer conformance surface: a recorded
 * command/event exchange from the reference web renderer is the fixture any
 * future native renderer (filament-vrm) must satisfy.
 */
object AvatarWireProtocol {
    const val VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun encodeCommand(command: AvatarRendererCommand): String =
        json.encodeToString(command)

    fun decodeCommand(text: String): AvatarRendererCommand =
        decode("command", text)

    fun encodeEvent(event: AvatarRendererEvent): String =
        json.encodeToString(event)

    fun decodeEvent(text: String): AvatarRendererEvent =
        decode("event", text)

    private inline fun <reified T> decode(kind: String, text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AvatarWireException("Malformed renderer $kind: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AvatarWireException("Malformed renderer $kind: ${e.message}", e)
        }
}

/** Thrown for malformed or protocol-incompatible wire messages. */
class AvatarWireException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// ---------------------------------------------------------------------------
// Host → renderer commands
// ---------------------------------------------------------------------------

@Serializable
sealed interface AvatarRendererCommand {
    /** Load the avatar at [url] (renderer-resolvable: http(s), blob, file). */
    @Serializable
    @SerialName("loadAvatar")
    data class LoadAvatar(
        val url: String,
        /** Manifest wire name of the format ("vrm1", "vrm0", "glb", "gltf"). */
        val format: String,
        /** Correlates the eventual Loaded/LoadFailed event. */
        val requestId: String,
    ) : AvatarRendererCommand

    @Serializable
    @SerialName("unload")
    data object Unload : AvatarRendererCommand

    @Serializable
    @SerialName("setExpression")
    data class SetExpression(val key: String, val weight: Float) : AvatarRendererCommand

    @Serializable
    @SerialName("setViseme")
    data class SetViseme(val key: String, val weight: Float) : AvatarRendererCommand

    @Serializable
    @SerialName("setMouthOpen")
    data class SetMouthOpen(val value: Float) : AvatarRendererCommand

    /** Aim head/eyes. Null-target is expressed with [ClearLookTarget]. */
    @Serializable
    @SerialName("setLookTarget")
    data class SetLookTarget(
        /** "world" (scene units) or "screen" (normalized 0..1, z ignored). */
        val space: String,
        val x: Float,
        val y: Float,
        val z: Float = 0f,
    ) : AvatarRendererCommand

    @Serializable
    @SerialName("clearLookTarget")
    data object ClearLookTarget : AvatarRendererCommand

    @Serializable
    @SerialName("playGesture")
    data class PlayGesture(val id: String, val fadeSeconds: Float) : AvatarRendererCommand

    @Serializable
    @SerialName("playAnimation")
    data class PlayAnimation(val id: String, val loop: Boolean) : AvatarRendererCommand

    @Serializable
    @SerialName("setAccessoryEnabled")
    data class SetAccessoryEnabled(val id: String, val enabled: Boolean) : AvatarRendererCommand
}

// ---------------------------------------------------------------------------
// Renderer → host events
// ---------------------------------------------------------------------------

@Serializable
sealed interface AvatarRendererEvent {
    /** First message after the renderer boots; host verifies [protocolVersion]. */
    @Serializable
    @SerialName("ready")
    data class Ready(val protocolVersion: Int) : AvatarRendererEvent

    /** The [AvatarRendererCommand.LoadAvatar] with [requestId] succeeded. */
    @Serializable
    @SerialName("avatarLoaded")
    data class AvatarLoaded(
        val requestId: String,
        /** What the renderer actually verified it can drive on this model. */
        val capabilities: AvatarCapabilities,
    ) : AvatarRendererEvent

    @Serializable
    @SerialName("avatarLoadFailed")
    data class AvatarLoadFailed(
        val requestId: String,
        val message: String,
    ) : AvatarRendererEvent

    /** Non-fatal renderer-side problem, surfaced for logging/UI. */
    @Serializable
    @SerialName("rendererError")
    data class RendererError(val message: String) : AvatarRendererEvent
}
