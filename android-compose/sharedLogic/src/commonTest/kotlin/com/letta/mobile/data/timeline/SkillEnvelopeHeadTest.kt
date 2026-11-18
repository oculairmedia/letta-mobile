package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.OVERSIZED
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.encoded
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.envelope
import com.letta.mobile.data.timeline.SkillEnvelopeFixtures.message
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A body too large to decode is recognised as a skill's instructions from its first bytes alone. */
class SkillEnvelopeHeadTest {
    @Test fun aHeadCutMidBodyStillReadsAsAnEnvelope() {
        assertTrue(opensSkillEnvelope(encoded(message(2, "user_message", envelope(OVERSIZED))).copyOf(SKILL_ENVELOPE_HEAD_BYTES)))
    }

    @Test fun anOrdinaryHeadDoesNot() {
        assertFalse(opensSkillEnvelope(encoded(message(2, "assistant_message", "Plain reply. ".repeat(300))).copyOf(SKILL_ENVELOPE_HEAD_BYTES)))
        // Frontmatter without the envelope's opening tag is not enough to hide a message read by its head.
        val frontmatter = "name: notes\ndescription: a pasted file\n" + "Line.\n".repeat(400)
        assertFalse(opensSkillEnvelope(encoded(message(2, "user_message", frontmatter)).copyOf(SKILL_ENVELOPE_HEAD_BYTES)))
    }
}
