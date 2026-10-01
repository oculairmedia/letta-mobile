package com.letta.mobile.desktop

import com.letta.mobile.desktop.data.DesktopSharedChatPageFlagStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSharedChatPageFlagTest {
    private val directory: Path = Files.createTempDirectory("shared-chat-flag")
    private val path: Path = directory.resolve("shared-chat-page.properties")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun parsesTruthyValuesCaseInsensitively() {
        listOf("1", "true", "TRUE", "yes", " Yes ", "on", "ON").forEach { raw ->
            assertTrue(parseSharedChatFlagValue(raw), "expected '$raw' to enable the flag")
        }
    }

    @Test
    fun rejectsFalsyAndMissingValues() {
        listOf(null, "", "0", "false", "no", "off", "enabled", "2").forEach { raw ->
            assertFalse(parseSharedChatFlagValue(raw), "expected '$raw' to leave the flag off")
        }
    }

    @Test
    fun offByDefaultWithNoOverrideAndNoSavedToggle() {
        val flag = DesktopSharedChatPageFlag(DesktopSharedChatPageFlagStore(path), noEnvironment())

        assertFalse(flag.enabled.value)
        assertFalse(flag.persistedEnabled.value)
        assertFalse(flag.forcedByEnvironment)
    }

    @Test
    fun systemPropertyForcesTheFlagOn() {
        val environment = SharedChatFlagEnvironment(
            systemProperty = { name -> "true".takeIf { name == SHARED_CHAT_SYSTEM_PROPERTY } },
            environmentVariable = { null },
        )

        val flag = DesktopSharedChatPageFlag(DesktopSharedChatPageFlagStore(path), environment)

        assertTrue(flag.forcedByEnvironment)
        assertTrue(flag.enabled.value)
        assertFalse(flag.persistedEnabled.value)
    }

    @Test
    fun environmentVariableForcesTheFlagOnAndSurvivesTogglingOff() {
        val environment = SharedChatFlagEnvironment(
            systemProperty = { null },
            environmentVariable = { name -> "1".takeIf { name == SHARED_CHAT_ENV_VARIABLE } },
        )
        val flag = DesktopSharedChatPageFlag(DesktopSharedChatPageFlagStore(path), environment)

        flag.setPersistedEnabled(false)

        assertTrue(flag.enabled.value)
    }

    @Test
    fun toggleIsPersistedAcrossInstances() {
        val first = DesktopSharedChatPageFlag(DesktopSharedChatPageFlagStore(path), noEnvironment())

        first.setPersistedEnabled(true)

        assertTrue(first.enabled.value)
        val reloaded = DesktopSharedChatPageFlag(DesktopSharedChatPageFlagStore(path), noEnvironment())
        assertTrue(reloaded.enabled.value)
        assertTrue(reloaded.persistedEnabled.value)

        reloaded.setPersistedEnabled(false)

        assertFalse(DesktopSharedChatPageFlagStore(path).load())
    }

    @Test
    fun corruptFileReadsAsOff() {
        path.writeText("chat.sharedPage.enabled=maybe\n")

        assertFalse(DesktopSharedChatPageFlagStore(path).load())
    }

    private fun noEnvironment() = SharedChatFlagEnvironment(systemProperty = { null }, environmentVariable = { null })
}
