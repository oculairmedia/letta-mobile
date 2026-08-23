package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.SkillArgumentNormalizer
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skill tool-call rendering tests for letta-mobile-45e2k.
 *
 * A synthetic skill-instruction envelope (role:user backend-injected model
 * context) must be filtered from presentation — never a user prose bubble,
 * never a dedicated chip. The canonical skill tool call (assistant TOOL_CALL
 * event) renders as a normal tool card through the existing UiToolCall path.
 */
class SkillToolRenderClassificationTest {

    // region Synthetic envelope filtering

    @Test
    fun `synthetic skill envelope is filtered from render items`() {
        val skillEnvelopeContent = """
            <asus-router>
            name: asus-router
            description: Pull stats from ASUS RT-AX82U router on demand — connected clients, CPU, memory, WAN, traffic, WiFi, VPN status.
            ---
            ## Usage

            ARGUMENTS: summary
            </asus-router>
        """.trimIndent()

        val messages = listOf(
            user("env-1", content = skillEnvelopeContent),
            assistant("a1", content = "Got router stats"),
        )

        val renderModel = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        // Only the assistant message should render; the envelope is filtered.
        assertEquals(1, renderModel.renderItems.size)
        assertTrue(renderModel.renderItems.single() is ChatRenderItem.Single)
    }

    @Test
    fun `synthetic envelope is absent from visible messages`() {
        val skillEnvelopeContent = buildLongSkillEnvelope("searxng")

        val messages = listOf(
            user("env-2", content = skillEnvelopeContent),
            assistant("a2", content = "Searched the web"),
        )

        val renderModel = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        assertTrue(renderModel.visibleMessages.none { it.id == "env-2" })
        assertEquals(1, renderModel.visibleMessages.size)
    }

    @Test
    fun `normal user prose mentioning skill-like text stays visible`() {
        // A short user message that happens to mention a skill name or contain
        // angle-bracket text must NOT be suppressed. The detector requires
        // 200+ chars and frontmatter signals, so short messages are safe.
        val normalUserMessage = user("u1", content = "Use the asus-router skill please")

        val renderModel = buildChatRenderModel(listOf(normalUserMessage), ChatDisplayMode.Interactive)

        assertEquals(1, renderModel.renderItems.size)
        assertTrue(renderModel.renderItems.single() is ChatRenderItem.Single)
        assertEquals("u1", (renderModel.renderItems.single() as ChatRenderItem.Single).message.id)
    }

    @Test
    fun `long user prose without envelope signals is not suppressed`() {
        // A long user message that does NOT match the envelope detector
        // (no frontmatter name:/description: lines, no literal <skill> tag)
        // must remain visible even though it exceeds MIN_ENVELOPE_CHARS.
        val longUserProse = buildString {
            append("Here is a very long user message that discusses various skills and tools ")
            repeat(30) { append("and continues with more text about the weather and router stats ") }
            append("and finally asks a normal question about the home network.")
        }
        assertTrue(longUserProse.length >= 200)

        val renderModel = buildChatRenderModel(
            listOf(user("long-u1", content = longUserProse)),
            ChatDisplayMode.Interactive,
        )

        assertEquals(1, renderModel.renderItems.size)
        assertTrue(renderModel.renderItems.single() is ChatRenderItem.Single)
    }

    // endregion

    // region Canonical tool-call rendering

    @Test
    fun `canonical skill tool call renders as normal Single with toolCalls`() {
        val skillToolCall = UiToolCall(
            name = "Skill",
            arguments = "searxng",
            result = "Search results...",
            displayTarget = "searxng",
            status = "success",
            toolCallId = "tc-1",
        )
        val messages = listOf(
            assistant("a1", content = "", toolCalls = listOf(skillToolCall), runId = "run-1"),
        )

        val renderModel = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        assertEquals(1, renderModel.renderItems.size)
        val single = renderModel.renderItems.single() as? ChatRenderItem.Single
        assertTrue(single != null)
        assertEquals("a1", single!!.message.id)
        assertEquals(1, single.message.toolCalls?.size)
        assertEquals("Skill", single.message.toolCalls!!.single().name)
    }

    @Test
    fun `skill tool call inside run block renders normally`() {
        val skillToolCall = UiToolCall(
            name = "Skill",
            arguments = "asus-router",
            result = "CPU: 12%",
            displayTarget = "asus-router",
            status = "success",
            toolCallId = "tc-run-1",
        )
        val messages = listOf(
            assistant("a1", content = "", toolCalls = listOf(skillToolCall), runId = "run-abc"),
            assistant("a2", content = "Here are the router stats.", runId = "run-abc"),
        )

        val renderModel = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        assertEquals(1, renderModel.renderItems.size)
        assertTrue(renderModel.renderItems.single() is ChatRenderItem.RunBlock)
        val block = renderModel.renderItems.single() as ChatRenderItem.RunBlock
        assertEquals("run-abc", block.runId)
    }

    // endregion

    // region Argument normalization

    @Test
    fun `normalize extracts skill from direct object JSON`() {
        val args = """{"skill":"searxng","query":"weather","language":"en"}"""
        val normalized = SkillArgumentNormalizer.normalize(args)

        assertEquals("searxng", normalized?.skillName)
        // The remaining args should not contain the skill field.
        assertTrue(normalized!!.normalizedArguments.contains("query"))
        assertTrue(normalized.normalizedArguments.contains("weather"))
        assertTrue(!normalized.normalizedArguments.contains("\"skill\""))
    }

    @Test
    fun `normalize extracts skill from single JSON-string wrapper`() {
        val inner = """{"skill":"asus-router","command":"status"}"""
        val args = JsonPrimitive(inner).toString()
        val normalized = SkillArgumentNormalizer.normalize(args)

        assertEquals("asus-router", normalized?.skillName)
        assertTrue(normalized!!.normalizedArguments.contains("command"))
    }

    @Test
    fun `normalize extracts skill from double JSON-string wrapper`() {
        val inner = """{"skill":"ghost","tag":"updates"}"""
        val args = JsonPrimitive(JsonPrimitive(inner).toString()).toString()
        val normalized = SkillArgumentNormalizer.normalize(args)

        assertEquals("ghost", normalized?.skillName)
        assertTrue(normalized!!.normalizedArguments.contains("tag"))
    }

    @Test
    fun `normalize returns null for non-skill JSON`() {
        val args = """{"query":"weather","language":"en"}"""
        assertNull(SkillArgumentNormalizer.normalize(args))
    }

    @Test
    fun `normalize returns null for malformed JSON`() {
        assertNull(SkillArgumentNormalizer.normalize("not json at all"))
        assertNull(SkillArgumentNormalizer.normalize("{invalid json"))
        assertNull(SkillArgumentNormalizer.normalize(""))
        assertNull(SkillArgumentNormalizer.normalize("   "))
    }

    @Test
    fun `normalize returns null when skill field is blank`() {
        assertNull(SkillArgumentNormalizer.normalize("""{"skill":""}"""))
        assertNull(SkillArgumentNormalizer.normalize("""{"skill":"  "}"""))
    }

    // endregion

    // region Helpers

    private fun buildLongSkillEnvelope(slug: String): String = buildString {
        append("<$slug>\n")
        append("name: $slug\n")
        append("description: A skill for testing envelope filtering with enough content to pass the minimum character threshold of the synthetic skill envelope detector.\n")
        append("---\n\n")
        append("## Documentation\n\n")
        append("This is test skill documentation with sufficient length.\n")
        repeat(5) { append("More documentation content on line $it.\n") }
        append("\nARGUMENTS: test-args\n")
        append("</$slug>")
    }

    private fun assistant(
        id: String,
        content: String = "a-$id",
        runId: String? = null,
        toolCalls: List<UiToolCall>? = null,
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        runId = runId,
        toolCalls = toolCalls,
    )

    private fun user(
        id: String,
        content: String = "u-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "user",
        content = content,
        timestamp = ts,
    )

    // endregion
}
