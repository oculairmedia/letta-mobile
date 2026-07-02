package com.letta.mobile.avatar.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Normalized `avatar.manifest.json`, generated once at import time by the
 * asset pipeline. Renderers and the catalog read THIS — never raw VRM/glTF
 * metadata — so VRM 0.x vs 1.0 vs plain GLB differences stay confined to the
 * importer.
 *
 * The schema is deliberately string-keyed (expression/viseme/bone keys, not
 * enums) so third-party tooling can produce and consume it without our code.
 */
@Serializable
data class AvatarManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val displayName: String,
    val format: AvatarFormat,
    val capabilities: AvatarCapabilities = AvatarCapabilities(),
    /** Normalized humanoid bone names present in the rig (VRM naming). */
    val humanoidBones: List<String> = emptyList(),
    /** Normalized expression keys the model exposes ([AvatarExpression.fromKey]). */
    val expressions: List<String> = emptyList(),
    /** Normalized viseme keys the model exposes ([AvatarViseme.fromKey]). */
    val visemes: List<String> = emptyList(),
    /** Embedded glTF animations, playable via [AvatarRuntime.playAnimation]. */
    val animations: List<AvatarAnimationInfo> = emptyList(),
    /** Toggleable accessory nodes ([AvatarRuntime.setAccessoryEnabled]). */
    val accessories: List<AvatarAccessoryInfo> = emptyList(),
    val stats: AvatarAssetStats = AvatarAssetStats(),
    val license: AvatarLicense = AvatarLicense(),
    val source: AvatarAssetSource = AvatarAssetSource(),
    /** Hash of the packaged runtime asset this manifest describes. */
    val sha256: String? = null,
    /** Non-fatal importer findings, surfaced in catalog/debug UI. */
    val warnings: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1

        /** Conventional manifest file name, stored beside the packed asset. */
        const val FILE_NAME = "avatar.manifest.json"
    }
}

/** One embedded glTF animation. */
@Serializable
data class AvatarAnimationInfo(
    val id: String,
    val name: String? = null,
    val durationSeconds: Float? = null,
    /** Whether this animation is intended to loop (idle cycles etc.). */
    val loopHint: Boolean = false,
)

/** One toggleable accessory (glasses, hat, prop) mapped to scene nodes. */
@Serializable
data class AvatarAccessoryInfo(
    val id: String,
    val name: String? = null,
    /** glTF node names this accessory shows/hides. */
    val nodeNames: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
)

/** Size/complexity numbers from the importer's asset report. */
@Serializable
data class AvatarAssetStats(
    val triangleCount: Long? = null,
    val textureCount: Int? = null,
    val materialCount: Int? = null,
    /** Axis-aligned bounds `[x, y, z]` in model space, if computed. */
    val boundsMin: List<Float>? = null,
    val boundsMax: List<Float>? = null,
)

/** Thrown by [AvatarManifestCodec.decode] for structurally invalid manifests. */
class AvatarManifestException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Reads/writes [AvatarManifest] JSON. Decoding is forward-tolerant (unknown
 * fields from newer writers are ignored) but rejects manifests from a schema
 * MAJOR version above ours, and enforces the invariants renderers rely on.
 */
object AvatarManifestCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun encode(manifest: AvatarManifest): String = json.encodeToString(manifest)

    fun decode(text: String): AvatarManifest {
        val manifest = try {
            json.decodeFromString<AvatarManifest>(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AvatarManifestException("Malformed avatar manifest: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AvatarManifestException("Malformed avatar manifest: ${e.message}", e)
        }
        if (manifest.schemaVersion > AvatarManifest.SCHEMA_VERSION) {
            throw AvatarManifestException(
                "Manifest schema version ${manifest.schemaVersion} is newer than " +
                    "supported version ${AvatarManifest.SCHEMA_VERSION}",
            )
        }
        if (manifest.id.isBlank()) {
            throw AvatarManifestException("Manifest id must not be blank")
        }
        return manifest
    }

    fun decodeOrNull(text: String): AvatarManifest? =
        try {
            decode(text)
        } catch (_: AvatarManifestException) {
            null
        }
}
