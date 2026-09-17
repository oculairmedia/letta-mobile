package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.MascotIdentity
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
        val keys = MascotShape.entries.map { candidateSceneKey(MascotIdentity.DEFAULT.copy(shape = it)) }

        assertEquals(MascotShape.entries.size, keys.toSet().size, "options would share a scene: $keys")
    }

    @Test
    fun colourAndTurnReskinTheBodyRatherThanAskingForAnotherScene() {
        // Scenes are never evicted, so keying on the whole identity would leak one per colour and
        // per slider step while the user drags. Only the body may split them.
        val red = MascotIdentity.DEFAULT.copy(argb = 0xFFFF0000.toInt(), rotationDegrees = 0)
        val blue = red.copy(argb = 0xFF0000FF.toInt(), rotationDegrees = 90)

        assertEquals(candidateSceneKey(red), candidateSceneKey(blue))
    }

    @Test
    fun candidateKeysCannotCollideWithAnAgentId() {
        // Agent ids are opaque and come from the backend; the prefix is what keeps a picker scene
        // from being handed out as some agent's live mascot, and vice versa.
        assertTrue(
            MascotShape.entries.all {
                candidateSceneKey(MascotIdentity.DEFAULT.copy(shape = it)).startsWith("mascot-candidate:")
            },
            "picker scenes must stay namespaced away from agent entries",
        )
    }
}
