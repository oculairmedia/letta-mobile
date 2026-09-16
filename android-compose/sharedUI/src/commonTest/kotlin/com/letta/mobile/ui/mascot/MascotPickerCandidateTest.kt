package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.MascotShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-0bvjw: the picker draws each option as the real mascot paused rather than a
 * hand-drawn silhouette, so every option now needs its own renderer scene. Scenes live in one
 * table keyed by string, and asking for a key with a different identity *re-skins that scene* —
 * so if two options shared a key they would overwrite each other and the whole grid would settle
 * on whichever shape was asked for last. Nothing else in the picker would look wrong; it would
 * simply show eight copies of one body. This pins the keys apart.
 */
class MascotPickerCandidateTest {

    @Test
    fun everyShapeGetsItsOwnSceneKey() {
        val keys = MascotShape.entries.map(::mascotPickerKey)

        assertEquals(MascotShape.entries.size, keys.toSet().size, "options would share a scene: $keys")
    }

    @Test
    fun candidateKeysCannotCollideWithAnAgentId() {
        // Agent ids are opaque and come from the backend; the prefix is what keeps a picker scene
        // from being handed out as some agent's live mascot, and vice versa.
        assertTrue(
            MascotShape.entries.all { mascotPickerKey(it).startsWith("mascot-picker:") },
            "picker scenes must stay namespaced away from agent entries",
        )
    }
}
