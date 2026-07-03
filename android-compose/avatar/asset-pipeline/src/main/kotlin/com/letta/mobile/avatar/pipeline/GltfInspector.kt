package com.letta.mobile.avatar.pipeline

import com.letta.mobile.avatar.core.AvatarAssetStats
import com.letta.mobile.avatar.core.AvatarDetection
import com.letta.mobile.avatar.core.GlbContainer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Structural findings for an asset at import time. [errors] fail the import;
 * [warnings] flow into the manifest for the catalog/debug UI.
 */
data class GltfInspectionReport(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val stats: AvatarAssetStats = AvatarAssetStats(),
)

/**
 * Import-time asset inspection seam. [BuiltinGltfInspector] does cheap
 * structural checks in-process; a future implementation can shell out to the
 * Khronos glTF Validator / glTF-Transform `inspect` and map their reports
 * into the same shape — the importer doesn't care which it gets.
 *
 * Implementations must REPORT problems (via [GltfInspectionReport.errors]),
 * never throw — a malformed asset is a rejection, not a crash.
 */
interface GltfInspector {
    fun inspect(bytes: ByteArray, detection: AvatarDetection): GltfInspectionReport
}

/**
 * In-process structural checks: glTF asset version, self-containedness
 * (external buffer/image URIs), and the count-style stats derivable from the
 * JSON alone (triangle counts need accessor decoding and stay null until the
 * external-tool inspector lands).
 */
object BuiltinGltfInspector : GltfInspector {
    private val json = Json { ignoreUnknownKeys = true }

    override fun inspect(bytes: ByteArray, detection: AvatarDetection): GltfInspectionReport =
        try {
            inspectOrThrow(bytes)
        } catch (t: Throwable) {
            // Malformed-but-parseable JSON must reject the import, not crash it.
            GltfInspectionReport(
                errors = listOf("Structural inspection failed: ${t.message ?: t::class.simpleName}"),
            )
        }

    private fun inspectOrThrow(bytes: ByteArray): GltfInspectionReport {
        val errors = mutableListOf<String>()

        val gltfJsonText = if (GlbContainer.looksLikeGlb(bytes)) {
            GlbContainer.parseOrNull(bytes)?.json
                ?: return GltfInspectionReport(errors = listOf("Unreadable GLB container"))
        } else {
            bytes.decodeToString()
        }
        val root = runCatching { json.parseToJsonElement(gltfJsonText) as? JsonObject }
            .getOrNull()
            ?: return GltfInspectionReport(errors = listOf("Asset JSON is not an object"))

        val assetVersion = (root["asset"] as? JsonObject)
            ?.get("version").primitiveContent()
        if (assetVersion == null) {
            errors += "Missing required asset.version"
        } else if (!assetVersion.startsWith("2.")) {
            errors += "Unsupported glTF version $assetVersion (need 2.x)"
        }

        // Any external URI resource (buffers OR images) means the packaged
        // asset would be missing data once separated from its source
        // directory. Until URI packing into GLB is implemented, these fail
        // the import instead of silently registering a broken avatar.
        val externalResources = externalUris(root, "buffers") + externalUris(root, "images")
        if (externalResources.isNotEmpty()) {
            errors += "External resources are not packed into the catalog yet " +
                "(re-export as a self-contained GLB): " + externalResources.joinToString()
        }

        return GltfInspectionReport(
            errors = errors,
            stats = AvatarAssetStats(
                textureCount = (root["textures"] as? JsonArray)?.size,
                materialCount = (root["materials"] as? JsonArray)?.size,
            ),
        )
    }

    private fun externalUris(root: JsonObject, key: String): List<String> =
        (root[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("uri").primitiveContent() }
            .filterNot { it.startsWith("data:") }

    /** Non-throwing primitive extraction — malformed nodes yield null. */
    private fun kotlinx.serialization.json.JsonElement?.primitiveContent(): String? =
        (this as? JsonPrimitive)?.content
}
