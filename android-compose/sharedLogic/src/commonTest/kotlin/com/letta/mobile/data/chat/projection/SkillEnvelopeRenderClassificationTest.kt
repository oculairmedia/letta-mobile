package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Skill-envelope classification tests, split out of ChatRenderModelBuilderTest
 * for cohesion (CodeScene critical Low Cohesion on the mixed file). Covers PR
 * #852: a synthetic skill-instruction envelope becomes an inspectable
 * SkillEnvelopeChip render item — never a user prose bubble — consistently
 * across live and hydrated paths.
 */
class SkillEnvelopeRenderClassificationTest {
    @Test
    fun `skill envelope renders as SkillEnvelopeChip not user prose bubble`() {
        // letta-mobile-o7ua9: a synthetic skill-instruction envelope must
        // render as a SkillEnvelopeChip (inspectable, collapsible chip) NOT
        // as a giant blue user bubble. This is the core TDD test for the
        // classification happening at the render-model layer.
        val skillEnvelopeContent = """
            <asus-router>
            name: asus-router
            description: Pull stats from ASUS RT-AX82U router on demand — connected clients, CPU, memory, WAN, traffic, WiFi, VPN status. Use when the user asks about router stats, network devices, connected clients, bandwidth usage, or router health.
            ---
            ## Usage
            
            ### Commands
            
            ```bash
            asus-router status
            asus-router clients
            ```
            
            ### Example output
            
            | Metric | Value |
            |--------|-------|
            | CPU    | 12%   |
            | Memory | 45%   |
            
            ARGUMENTS: summary
            </asus-router>
        """.trimIndent()

        val messages = listOf(
            user("skill-env-1", content = skillEnvelopeContent),
            assistant("a1", content = "Got router stats"),
        )

        val renderModel = buildChatRenderModel(messages, ChatDisplayMode.Interactive)

        assertEquals(2, renderModel.renderItems.size)
        // Reversed order: assistant first, then skill chip
        assertTrue(renderModel.renderItems[0] is ChatRenderItem.Single, "Assistant should be a Single")
        assertTrue(renderModel.renderItems[1] is ChatRenderItem.SkillEnvelopeChip, "Skill envelope should be a SkillEnvelopeChip")

        val chip = renderModel.renderItems[1] as ChatRenderItem.SkillEnvelopeChip
        assertEquals("skill-env-1", chip.messageId)
        assertEquals("asus-router", chip.slug)
        assertEquals("asus-router", chip.name)
        assertEquals(
            "Pull stats from ASUS RT-AX82U router on demand — connected clients, CPU, memory, WAN, traffic, WiFi, VPN status. Use when the user asks about router stats, network devices, connected clients, bandwidth usage, or router health.",
            chip.description
        )
        assertEquals("summary", chip.args)
    }

    @Test
    fun `skill envelope classification is consistent across live and hydrated paths`() {
        // letta-mobile-o7ua9 (P1): the classification must produce the same
        // render item whether the message arrived via live stream, hydration,
        // or reload. All three paths converge on buildChatRenderModel so this
        // test validates the unified behaviour.
        val skillEnvelopeContent = """
            <test-skill>
            name: test-skill
            description: Test skill that demonstrates consistent classification across all message delivery paths including live streaming, hydration from server history, and full timeline reload after app restart
            ---
            ## Documentation
            
            This is a test skill with enough content to pass the minimum envelope size threshold.
            The synthetic skill envelope detector requires at least 200 characters to avoid
            false positives on short user messages.
            
            ARGUMENTS: foo
            </test-skill>
        """.trimIndent()

        val liveMessage = user("live-1", content = skillEnvelopeContent)
        val hydratedMessage = user("hydrated-1", content = skillEnvelopeContent)

        val liveRender = buildChatRenderModel(listOf(liveMessage), ChatDisplayMode.Interactive)
        val hydratedRender = buildChatRenderModel(listOf(hydratedMessage), ChatDisplayMode.Interactive)

        assertTrue(liveRender.renderItems.single() is ChatRenderItem.SkillEnvelopeChip)
        assertTrue(hydratedRender.renderItems.single() is ChatRenderItem.SkillEnvelopeChip)

        val liveChip = liveRender.renderItems.single() as ChatRenderItem.SkillEnvelopeChip
        val hydratedChip = hydratedRender.renderItems.single() as ChatRenderItem.SkillEnvelopeChip

        assertEquals("test-skill", liveChip.slug)
        assertEquals("test-skill", hydratedChip.slug)
    }

    @Test
    fun `normal user message is not misclassified as skill envelope`() {
        val normalUserMessage = user("u1", content = "What is the weather today?")

        val renderModel = buildChatRenderModel(listOf(normalUserMessage), ChatDisplayMode.Interactive)

        assertEquals(1, renderModel.renderItems.size)
        assertTrue(renderModel.renderItems.single() is ChatRenderItem.Single, "Normal user message should remain a Single")
    }

    private fun assistant(
        id: String,
        content: String = "a-$id",
        runId: String? = null,
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        runId = runId,
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
}
