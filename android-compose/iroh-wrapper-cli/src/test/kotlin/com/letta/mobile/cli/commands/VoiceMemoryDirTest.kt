package com.letta.mobile.cli.commands

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Where the host reads the agents' `voice` blocks: the backend the App Server really uses. */
class VoiceMemoryDirTest {
    @Test
    fun `an owned App Server's default backend is where the voice blocks are read`() {
        val owned = effectiveA2aBackendDir(null, true, "/home/host")
        assertEquals(File("/home/host", ".letta/lc-local-backend"), voiceMemoryDir(owned, null))
        assertEquals(owned, voiceMemoryDir(owned, "/elsewhere"), "the effective directory wins over the environment")
    }

    @Test
    fun `an explicit directory is used, else the environment, else none`() {
        assertEquals(File("explicit"), voiceMemoryDir(effectiveA2aBackendDir("explicit", false, null), "/env"))
        assertEquals(File("/env"), voiceMemoryDir(effectiveA2aBackendDir(null, false, null), "/env"))
        assertNull(voiceMemoryDir(null, " "))
        assertNull(voiceMemoryDir(null, null))
    }
}
