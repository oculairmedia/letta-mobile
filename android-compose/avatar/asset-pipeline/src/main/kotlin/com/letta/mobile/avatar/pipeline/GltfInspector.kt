package com.letta.mobile.avatar.pipeline

import com.letta.mobile.avatar.core.AvatarAssetStats
import com.letta.mobile.avatar.core.AvatarDetection
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.GlbContainer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 */
interface GltfInspector {
    fun inspect(bytes: ByteArray, detection: AvatarDetection): GltfInspectionReport
}

/**
 * In-process structural checks: glTF asset version, buffer packing, and the
 * count-style stats derivable from the JSON alone (triangle counts need
 * accessor decoding and stay null until the external-tool inspector lands).
 */
object BuiltinGltfInspector : GltfInspector {
    private val json = Json { ignoreUnknownKeys = true }

    override fun inspect(bytes: ByteArray, detection: AvatarDetection): GltfInspectionReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val gltfJsonText = if (GlbContainer.looksLikeGlb(bytes)) {
            GlbContainer.parseOrNull(bytes)?.json
                ?: return GltfInspectionReport(errors = listOf("Unreadable GLB container"))
        } else {
            bytes.decodeToString()
        }
        val root = runCatching { json.parseToJsonElement(gltfJsonText) as JsonObject }
            .getOrElse { return GltfInspectionReport(errors = listOf("Asset JSON is not an object")) }

        val assetVersion = (root["asset"] as? JsonObject)
            ?.get("version")?.jsonPrimitive?.content
        if (assetVersion == null) {
            errors += "Missing required asset.version"
        } else if (!assetVersion.startsWith("2.")) {
            errors += "Unsupported glTF version $assetVersion (need 2.x)"
        }

        // External buffer URIs mean the asset isn't self-contained; packing
        // them into a GLB is future pipeline work, so flag it loudly.
        val externalBuffers = (root["buffers"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("uri")?.jsonPrimitive?.content }
            .filterNot { it.startsWith("data:") }
        if (externalBuffers.isNotEmpty()) {
            if (detection.format == AvatarFormat.GLTF) {
                warnings += "External buffers are not packed into GLB yet: " +
                    externalBuffers.joinToString()
            } else {
                errors += "GLB references external buffers: " + externalBuffers.joinToString()
            }
        }

        return GltfInspectionReport(
            errors = errors,
            warnings = warnings,
            stats = AvatarAssetStats(
                textureCount = (root["textures"] as? JsonArray)?.size,
                materialCount = (root["materials"] as? JsonArray)?.size,
            ),
        )
    }
}
