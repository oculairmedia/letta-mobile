package com.letta.mobile.data.voice.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceCoreTest {
    @Test
    fun aBlockHoldsOneSanitisedLine() {
        assertEquals("A calm, low voice.", VoiceCore.fromBlock("  A calm,\n\tlow   voice.\u0007 ")?.description)
    }

    @Test
    fun anEmptyOrMissingBlockHoldsNoCore() {
        assertNull(VoiceCore.fromBlock(null))
        assertNull(VoiceCore.fromBlock("   \n "))
        assertNull(VoiceCore.fromBlock("---\ndescription: voice\n---\n"))
    }

    @Test
    fun frontMatterIsSkipped() {
        assertEquals("A bright voice.", VoiceCore.fromBlock("---\ndescription: How the agent sounds\n---\nA bright voice.")?.description)
    }

    @Test
    fun frontMatterNeedsBothDelimiterLines() {
        val stored = VoiceCore.blockValueOf("--- A quiet voice.")
        assertEquals("--- A quiet voice.", stored)
        assertEquals("--- A quiet voice.", VoiceCore.fromBlock(stored)?.description)
        assertEquals("--- never closed", VoiceCore.fromBlock("---\nnever closed")?.description)
        assertEquals("A bright voice.", VoiceCore.fromBlock("---  \r\nlimit: 400\r\n---\r\nA bright voice.")?.description)
        val header = "---\nlimit: 400\n--- not a close\n---\n"
        assertEquals(header + "A bright voice.", VoiceCore.blockValueOf("A bright voice.", header + "Old."))
        assertEquals("A bright voice.", VoiceCore.blockValueOf("A bright voice.", "--- An old voice."))
    }

    @Test
    fun anOverlongBlockIsCutAtTheLastWholeWord() {
        val core = VoiceCore.fromBlock("word ".repeat(60))!!.description
        assertTrue(core.length <= VoiceCore.MAX_LENGTH)
        assertTrue(core.endsWith("word"))
    }

    @Test
    fun aSettingRefusesTooLongButClearsWithBlank() {
        assertNull(VoiceCore.blockValueOf("a".repeat(VoiceCore.MAX_LENGTH + 1)))
        assertEquals("a".repeat(VoiceCore.MAX_LENGTH), VoiceCore.blockValueOf("a".repeat(VoiceCore.MAX_LENGTH)))
        assertEquals("", VoiceCore.blockValueOf("  "))
        assertEquals("Two lines.", VoiceCore.blockValueOf("Two\nlines."))
    }

    @Test
    fun aSettingKeepsTheBlocksFrontMatter() {
        val frontMatter = "---\ndescription: How this agent sounds\nlimit: 400\n---\n"
        val existing = frontMatter + "A calm voice."
        assertEquals(frontMatter + "A bright voice.", VoiceCore.blockValueOf("A bright voice.", existing))
        assertEquals(frontMatter, VoiceCore.blockValueOf("", existing))
        assertEquals("A bright voice.", VoiceCore.blockValueOf("A bright voice.", "A calm voice."))
        assertNull(VoiceCore.fromBlock(VoiceCore.blockValueOf("", existing)))
    }

    @Test
    fun aCoreNeverPrintsItsDescription() {
        assertTrue("calm" !in VoiceCore.fromBlock("A calm voice.").toString())
    }
}
