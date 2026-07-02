package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarFormatDetectorTest {
    @Test
    fun plainGlbDetectsAsGlbWithNoHumanoidCapabilities() {
        val detection = AvatarFormatDetector.detect(
            buildGlb("""{"asset":{"version":"2.0"}}""", bin = null),
        )

        assertEquals(AvatarFormat.GLB, detection.format)
        assertFalse(detection.capabilities.supportsHumanoid)
        assertFalse(detection.capabilities.supportsExpressions)
        assertTrue(detection.humanoidBones.isEmpty())
    }

    @Test
    fun gltfJsonDocumentDetectsAsGltf() {
        val detection = AvatarFormatDetector.detect(
            """  {"asset":{"version":"2.0"}}""".encodeToByteArray(),
        )
        assertEquals(AvatarFormat.GLTF, detection.format)
    }

    @Test
    fun vrm1AssetNormalizesBonesExpressionsAndVisemes() {
        val json = """
            {
              "asset": {"version": "2.0"},
              "extensionsUsed": ["VRMC_vrm", "VRMC_springBone"],
              "extensions": {
                "VRMC_vrm": {
                  "humanoid": {"humanBones": {"hips": {"node": 1}, "head": {"node": 2}}},
                  "expressions": {
                    "preset": {"happy": {}, "aa": {}, "ih": {}},
                    "custom": {"wink": {}}
                  }
                }
              }
            }
        """.trimIndent()

        val detection = AvatarFormatDetector.detect(buildGlb(json, bin = null))

        assertEquals(AvatarFormat.VRM_1, detection.format)
        assertEquals(listOf("hips", "head"), detection.humanoidBones)
        assertEquals(listOf("happy", "wink"), detection.expressions)
        assertEquals(listOf("aa", "ih"), detection.visemes)
        with(detection.capabilities) {
            assertTrue(supportsHumanoid)
            assertTrue(supportsExpressions)
            assertTrue(supportsVisemes)
            assertTrue(supportsLookAt)
            assertTrue(supportsSpringBones)
            assertFalse(supportsEmbeddedAnimations)
        }
    }

    @Test
    fun vrm0AssetNormalizesLegacyPresetNames() {
        val json = """
            {
              "asset": {"version": "2.0"},
              "extensionsUsed": ["VRM"],
              "extensions": {
                "VRM": {
                  "humanoid": {"humanBones": [
                    {"bone": "hips", "node": 1},
                    {"bone": "head", "node": 2}
                  ]},
                  "blendShapeMaster": {"blendShapeGroups": [
                    {"presetName": "joy", "name": "Joy"},
                    {"presetName": "sorrow", "name": "Sorrow"},
                    {"presetName": "a", "name": "A"},
                    {"presetName": "unknown", "name": "CatEars"}
                  ]},
                  "secondaryAnimation": {"boneGroups": [{"bones": [3]}]}
                }
              }
            }
        """.trimIndent()

        val detection = AvatarFormatDetector.detect(buildGlb(json, bin = null))

        assertEquals(AvatarFormat.VRM_0, detection.format)
        assertEquals(listOf("hips", "head"), detection.humanoidBones)
        // joy -> happy, sorrow -> sad; unknown preset falls back to its name.
        assertEquals(listOf("happy", "sad", "CatEars"), detection.expressions)
        // a -> aa (VRM 1.0 viseme key space).
        assertEquals(listOf("aa"), detection.visemes)
        assertTrue(detection.capabilities.supportsSpringBones)
        assertTrue(detection.warnings.any { it.contains("VRM 0.x") })
    }

    @Test
    fun embeddedAnimationsAreListedWithStableIds() {
        val json = """
            {
              "asset": {"version": "2.0"},
              "animations": [{"name": "Idle"}, {}]
            }
        """.trimIndent()

        val detection = AvatarFormatDetector.detect(buildGlb(json, bin = null))

        assertTrue(detection.capabilities.supportsEmbeddedAnimations)
        assertEquals(listOf("Idle", "anim-1"), detection.animations.map { it.id })
    }

    @Test
    fun nonGltfBytesAreRejected() {
        assertFailsWith<AvatarDetectionException> {
            AvatarFormatDetector.detect(byteArrayOf(0, 1, 2, 3))
        }
        assertEquals(null, AvatarFormatDetector.detectOrNull(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun detectionBuildsManifestThroughSingleNormalizationPath() {
        val json = """
            {
              "asset": {"version": "2.0"},
              "extensionsUsed": ["VRMC_vrm"],
              "extensions": {
                "VRMC_vrm": {
                  "humanoid": {"humanBones": {"head": {"node": 1}}},
                  "expressions": {"preset": {"happy": {}}}
                }
              }
            }
        """.trimIndent()

        val manifest = AvatarFormatDetector.detect(buildGlb(json, bin = null)).toManifest(
            id = "avatar-1",
            displayName = "Test Avatar",
            license = AvatarLicense(allowRedistribution = true, licenseName = "CC0"),
            sha256 = "abc123",
        )

        assertEquals(AvatarFormat.VRM_1, manifest.format)
        assertEquals(listOf("happy"), manifest.expressions)
        assertEquals("abc123", manifest.sha256)
        // Round-trips through the codec.
        val decoded = AvatarManifestCodec.decode(AvatarManifestCodec.encode(manifest))
        assertEquals(manifest, decoded)
        // And projects to a catalog model.
        val model = manifest.toModel(uri = "content://avatars/avatar-1.glb")
        assertEquals("avatar-1", model.id)
        assertEquals(AvatarFormat.VRM_1, model.format)
    }
}
