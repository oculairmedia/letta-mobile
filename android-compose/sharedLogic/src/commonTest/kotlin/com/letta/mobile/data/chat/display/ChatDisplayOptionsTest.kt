package com.letta.mobile.data.chat.display

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.5 (G91): display toggles hide rows, persist, and drive the transcript. */
class ChatDisplayOptionsTest {
    private val prompt = UiMessage(id = "u1", role = "user", content = "Fix the build", timestamp = "t1")
    private val thought = UiMessage(id = "r1", role = "assistant", content = "Look at gradle\nthen run it", timestamp = "t2", isReasoning = true)
    private val reply = UiMessage(
        id = "a1",
        role = "assistant",
        content = "Done.",
        timestamp = "t3",
        toolCalls = listOf(UiToolCall(name = "Bash", arguments = "{\"command\":\"echo ```\"}", result = "ok")),
    )
    private val compaction = UiMessage(id = "c1", role = "system", content = "Summary of earlier work", timestamp = "t4", isCompaction = true)
    private val messages = listOf(prompt, thought, reply, compaction)

    @Test
    fun defaultsShowEverythingAndKeepTheSameList() {
        assertSame(messages, ChatDisplayOptions.Default.visible(messages))
    }

    @Test
    fun hidingReasoningAndCompactionsDropsOnlyThoseRows() {
        val options = ChatDisplayOptions(showReasoning = false, showCompactions = false)
        assertEquals(listOf(prompt, reply), options.visible(messages))
        assertTrue(options.hides(thought))
        assertFalse(options.hides(reply))
    }

    @Test
    fun optionsSurviveARestart() {
        val store = MemoryStore()
        ChatDisplayPrefs(store).update { it.copy(showReasoning = false, showTokenUsage = false) }

        val restarted = ChatDisplayPrefs(store).options.value

        assertEquals(ChatDisplayOptions(showReasoning = false, showCompactions = true, showTokenUsage = false), restarted)
    }

    @Test
    fun textSizeStepsMapToTheNearestScale() {
        assertEquals(ChatTextSize.Small, ChatTextSize.nearest(0.7f))
        assertEquals(ChatTextSize.Medium, ChatTextSize.nearest(1.04f))
        assertEquals(ChatTextSize.Large, ChatTextSize.nearest(1.6f))
    }

    @Test
    fun transcriptHasTheTitleEachSpeakerAndToolCalls() {
        val markdown = TranscriptMarkdown.build(TranscriptInput(title = "Build fix", agentName = "Meridian", messages = messages))

        assertTrue(markdown.startsWith("# Build fix\n"))
        assertTrue("## You\n\nFix the build" in markdown)
        assertTrue("## Meridian (thinking)\n\n> Look at gradle\n> then run it" in markdown)
        assertTrue("## Meridian\n\nDone.\n\n**Tool:** `Bash`" in markdown)
        // A fence longer than the backticks inside the arguments, so the block cannot break out.
        assertTrue("````\n{\"command\":\"echo ```\"}\n````" in markdown)
        assertTrue("## Conversation compacted" in markdown)
        assertTrue(markdown.endsWith("\n"))
    }

    @Test
    fun transcriptFollowsTheDisplayOptions() {
        val markdown = TranscriptMarkdown.build(
            TranscriptInput("Build fix", "Meridian", messages, ChatDisplayOptions(showReasoning = false, showCompactions = false)),
        )
        assertFalse("thinking" in markdown)
        assertFalse("compacted" in markdown)
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("build-fix-v2.md", TranscriptMarkdown.fileName("  Build fix: v2!  "))
        assertEquals("transcript.md", TranscriptMarkdown.fileName("???"))
    }

    private class MemoryStore : SecureSettingsStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }
}
