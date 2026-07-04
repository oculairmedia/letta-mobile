package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class AvatarAnimationFormatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun wireNameMatchesSerialNameForEveryFormat() {
        // wireName (used on the renderer protocol) must stay in lockstep with the
        // @SerialName the enum serializes to, for all three formats.
        val expected = mapOf(
            AvatarAnimationFormat.VRMA to "vrma",
            AvatarAnimationFormat.FBX to "fbx",
            AvatarAnimationFormat.GLB to "glb",
        )
        assertEquals(expected.size, AvatarAnimationFormat.entries.size, "a format is missing coverage")
        for (format in AvatarAnimationFormat.entries) {
            assertEquals(expected[format], format.wireName, "wireName for $format")
        }
    }

    @Test
    fun glbSourceRoundTripsThroughSerialization() {
        val source = AvatarAnimationSource("idle", "file:///idle.glb", AvatarAnimationFormat.GLB)
        val encoded = json.encodeToString(AvatarAnimationSource.serializer(), source)
        assertEquals(true, "\"format\":\"glb\"" in encoded, encoded)
        assertEquals(source, json.decodeFromString(AvatarAnimationSource.serializer(), encoded))
    }
}
