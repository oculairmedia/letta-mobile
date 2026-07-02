package com.letta.mobile.avatar.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result of sniffing an asset's bytes: format + normalized metadata. */
data class AvatarDetection(
    val format: AvatarFormat,
    /** glTF `extensionsUsed`, verbatim. */
    val extensionsUsed: Set<String>,
    val capabilities: AvatarCapabilities,
    /** Normalized humanoid bone names found in the VRM rig (VRM naming). */
    val humanoidBones: List<String>,
    /** Normalized expression keys ([AvatarExpression] key space). */
    val expressions: List<String>,
    /** Normalized viseme keys ([AvatarViseme] key space). */
    val visemes: List<String>,
    /** Embedded animation infos (ids are `"anim-<index>"` when unnamed). */
    val animations: List<AvatarAnimationInfo>,
    val warnings: List<String>,
)

/** Thrown by [AvatarFormatDetector.detect] for bytes that aren't glTF at all. */
class AvatarDetectionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Sniffs `.vrm`/`.glb`/`.gltf` bytes and normalizes VRM 1.0 (`VRMC_vrm`) and
 * VRM 0.x (`VRM`) metadata into the shared capability/expression model. This
 * is the import-side primitive the asset pipeline builds manifests from; it
 * reads only the glTF JSON — geometry is never touched.
 */
object AvatarFormatDetector {
    private const val VRM_1_EXTENSION = "VRMC_vrm"
    private const val VRM_0_EXTENSION = "VRM"
    private const val VRM_SPRING_BONE_EXTENSION = "VRMC_springBone"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * VRM 0.x blend-shape preset → normalized key. Lip-sync presets map to
     * [AvatarViseme] keys, emotion presets to [AvatarExpression] keys.
     * (VRM 1.0 already uses the normalized names.)
     */
    private val vrm0PresetToNormalized = mapOf(
        "joy" to "happy",
        "angry" to "angry",
        "sorrow" to "sad",
        "fun" to "relaxed",
        "surprised" to "surprised",
        "neutral" to "neutral",
        "a" to "aa",
        "i" to "ih",
        "u" to "ou",
        "e" to "ee",
        "o" to "oh",
    )

    private val visemeKeys = AvatarViseme.presets.map { it.key }.toSet()

    /**
     * Detect the format and capabilities of raw asset [bytes]. Accepts a GLB
     * container or a JSON `.gltf` document.
     */
    fun detect(bytes: ByteArray): AvatarDetection {
        val (gltfJsonText, container) = when {
            GlbContainer.looksLikeGlb(bytes) -> {
                val glb = try {
                    GlbContainer.parse(bytes)
                } catch (e: GlbFormatException) {
                    throw AvatarDetectionException(e.message ?: "Invalid GLB", e)
                }
                glb.json to AvatarFormat.GLB
            }
            looksLikeJson(bytes) -> bytes.decodeToString() to AvatarFormat.GLTF
            else -> throw AvatarDetectionException(
                "Not a GLB container or glTF JSON document",
            )
        }

        val root = try {
            json.parseToJsonElement(gltfJsonText).jsonObject
        } catch (e: Exception) {
            throw AvatarDetectionException("Asset JSON chunk is not valid JSON", e)
        }

        val extensions = root.stringArray("extensionsUsed").toSet()
        val warnings = mutableListOf<String>()

        val format = when {
            VRM_1_EXTENSION in extensions -> AvatarFormat.VRM_1
            VRM_0_EXTENSION in extensions -> AvatarFormat.VRM_0
            else -> container
        }
        if (format == AvatarFormat.VRM_0) {
            warnings += "VRM 0.x asset: normalized at import; consider re-exporting as VRM 1.0"
        }

        val animations = detectAnimations(root)
        val (bones, morphKeys) = when (format) {
            AvatarFormat.VRM_1 -> extractVrm1(root)
            AvatarFormat.VRM_0 -> extractVrm0(root)
            else -> emptyList<String>() to emptyList()
        }
        val expressions = morphKeys.filter { it !in visemeKeys }
        val visemes = morphKeys.filter { it in visemeKeys }

        val capabilities = AvatarCapabilities(
            supportsHumanoid = format.isHumanoidProfile && bones.isNotEmpty(),
            supportsExpressions = expressions.isNotEmpty(),
            supportsVisemes = visemes.isNotEmpty(),
            // VRM look-at needs eye bones or a lookAt block; a humanoid head
            // bone is the minimum a renderer can drive procedurally.
            supportsLookAt = format.isHumanoidProfile && "head" in bones,
            supportsSpringBones = VRM_SPRING_BONE_EXTENSION in extensions ||
                (format == AvatarFormat.VRM_0 && root.hasVrm0SecondaryAnimation()),
            supportsEmbeddedAnimations = animations.isNotEmpty(),
            // Accessories are a manifest-authoring concept, not a glTF one.
            supportsAccessories = false,
        )

        return AvatarDetection(
            format = format,
            extensionsUsed = extensions,
            capabilities = capabilities,
            humanoidBones = bones,
            expressions = expressions,
            visemes = visemes,
            animations = animations,
            warnings = warnings,
        )
    }

    fun detectOrNull(bytes: ByteArray): AvatarDetection? =
        try {
            detect(bytes)
        } catch (_: AvatarDetectionException) {
            null
        }

    // --- VRM 1.0: extensions.VRMC_vrm ---------------------------------------

    private fun extractVrm1(root: JsonObject): Pair<List<String>, List<String>> {
        val vrm = root.obj("extensions")?.obj(VRM_1_EXTENSION)
            ?: return emptyList<String>() to emptyList()

        // humanoid.humanBones is a map of normalized bone name -> { node }.
        val bones = vrm.obj("humanoid")?.obj("humanBones")?.keys?.toList().orEmpty()

        // expressions.preset is a map keyed by preset name (happy, aa, …);
        // expressions.custom is keyed by author-chosen names.
        val expressionsObj = vrm.obj("expressions")
        val morphKeys = buildList {
            expressionsObj?.obj("preset")?.keys?.forEach { add(it) }
            expressionsObj?.obj("custom")?.keys?.forEach { add(it) }
        }
        return bones to morphKeys
    }

    // --- VRM 0.x: extensions.VRM --------------------------------------------

    private fun extractVrm0(root: JsonObject): Pair<List<String>, List<String>> {
        val vrm = root.obj("extensions")?.obj(VRM_0_EXTENSION)
            ?: return emptyList<String>() to emptyList()

        // humanoid.humanBones is an ARRAY of { bone: "hips", node: n }.
        val bones = vrm.obj("humanoid")?.get("humanBones")?.let { element ->
            (element as? JsonArray)?.mapNotNull { entry ->
                (entry as? JsonObject)?.get("bone")?.jsonPrimitive?.content
            }
        }.orEmpty()

        // blendShapeMaster.blendShapeGroups is an array of groups with a
        // presetName ("joy", "a", …) or "unknown" + custom name.
        val groups = vrm.obj("blendShapeMaster")?.get("blendShapeGroups") as? JsonArray
        val morphKeys = groups.orEmpty().mapNotNull { entry ->
            val group = entry as? JsonObject ?: return@mapNotNull null
            val preset = group["presetName"]?.jsonPrimitive?.content?.lowercase()
            when {
                preset != null && preset in vrm0PresetToNormalized ->
                    vrm0PresetToNormalized[preset]
                else -> group["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }
        }.distinct()
        return bones to morphKeys
    }

    private fun JsonObject.hasVrm0SecondaryAnimation(): Boolean =
        obj("extensions")?.obj(VRM_0_EXTENSION)?.obj("secondaryAnimation")
            ?.let { (it["boneGroups"] as? JsonArray)?.isNotEmpty() == true } == true

    // --- Shared glTF ---------------------------------------------------------

    private fun detectAnimations(root: JsonObject): List<AvatarAnimationInfo> {
        val animations = root["animations"] as? JsonArray ?: return emptyList()
        return animations.mapIndexed { index, entry ->
            val name = (entry as? JsonObject)?.get("name")?.jsonPrimitive?.content
            AvatarAnimationInfo(
                id = name?.takeIf { it.isNotBlank() } ?: "anim-$index",
                name = name,
            )
        }
    }

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val head = bytes.take(64).toByteArray().decodeToString()
        return head.trimStart().startsWith("{")
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            .orEmpty()
}
