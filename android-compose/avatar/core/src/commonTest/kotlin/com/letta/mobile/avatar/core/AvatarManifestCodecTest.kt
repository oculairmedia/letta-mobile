package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AvatarManifestCodecTest {
    private val manifest = AvatarManifest(
        id = "avatar-1",
        displayName = "Test Avatar",
        format = AvatarFormat.VRM_1,
        capabilities = AvatarCapabilities(supportsHumanoid = true, supportsExpressions = true),
        humanoidBones = listOf("hips", "head"),
        expressions = listOf("happy", "wink"),
        visemes = listOf("aa"),
        animations = listOf(AvatarAnimationInfo(id = "Idle", name = "Idle", loopHint = true)),
        license = AvatarLicense(licenseName = "CC0", allowRedistribution = true),
        source = AvatarAssetSource(kind = "vroid-studio"),
        sha256 = "abc123",
        warnings = listOf("something minor"),
    )

    @Test
    fun roundTripsThroughJson() {
        assertEquals(manifest, AvatarManifestCodec.decode(AvatarManifestCodec.encode(manifest)))
    }

    @Test
    fun ignoresUnknownFieldsFromNewerWriters() {
        val decoded = AvatarManifestCodec.decode(
            """{"schemaVersion":1,"id":"x","displayName":"X","format":"glb","someFutureField":42}""",
        )
        assertEquals("x", decoded.id)
        assertEquals(AvatarFormat.GLB, decoded.format)
    }

    @Test
    fun usesStableWireNamesForFormats() {
        val encoded = AvatarManifestCodec.encode(manifest)
        check("\"vrm1\"" in encoded) { "expected vrm1 wire name in: $encoded" }
    }

    @Test
    fun rejectsNewerSchemaVersion() {
        assertFailsWith<AvatarManifestException> {
            AvatarManifestCodec.decode(
                """{"schemaVersion":99,"id":"x","displayName":"X","format":"glb"}""",
            )
        }
    }

    @Test
    fun rejectsBlankIdAndMalformedJson() {
        assertFailsWith<AvatarManifestException> {
            AvatarManifestCodec.decode("""{"schemaVersion":1,"id":"  ","displayName":"X","format":"glb"}""")
        }
        assertFailsWith<AvatarManifestException> {
            AvatarManifestCodec.decode("not json")
        }
        assertNull(AvatarManifestCodec.decodeOrNull("not json"))
    }
}
