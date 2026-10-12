package com.letta.mobile.data.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.6 (G31): the `@agent` reference's shape, parsing, insertion and removal. */
class AgentMentionsTest {
    private val bot = MentionableAgent(id = "agent-123", name = "Research Bot")

    @Test
    fun theReferenceCarriesTheNameAndTheIdTheMessagingToolsAddress() {
        assertEquals("@Research Bot (agent agent-123)", AgentMentions.reference(bot))
        assertEquals("Research Bot (agent agent-123)", AgentMentions.insertText(bot))
    }

    @Test
    fun theSuggestionIsAnAgentWhoseInsertTextIsTheReference() {
        val mention = AgentMentions.mentionable(bot)
        assertEquals(MentionKind.Agent, mention.kind)
        assertEquals("Research Bot", mention.label)
        assertEquals(AgentMentions.insertText(bot), mention.insertText)
    }

    @Test
    fun theConversationsOwnAgentAndDuplicatesAreNotOffered() {
        val roster = listOf(bot, MentionableAgent("agent-self", "Me"), bot, MentionableAgent("", "No id"))
        val offered = AgentMentions.mentionables(roster, excludeAgentId = "agent-self")
        assertEquals(listOf("agent-123"), offered.map { it.id })
    }

    @Test
    fun aNameThatWouldBreakTheShapeIsCleanedAndABlankNameFallsBackToTheId() {
        assertEquals("@Bot v2 (agent agent-1)", AgentMentions.reference(MentionableAgent("agent-1", "Bot (v2)")))
        assertEquals("@agent-2 (agent agent-2)", AgentMentions.reference(MentionableAgent("agent-2", " @() ")))
    }

    @Test
    fun parseFindsEveryReferenceWithItsSpan() {
        val text = "Ask @Research Bot (agent agent-123) and @Ops (agent agent-local-9) about it"
        val refs = AgentMentions.parse(text)
        assertEquals(listOf("Research Bot" to "agent-123", "Ops" to "agent-local-9"), refs.map { it.name to it.agentId })
        assertEquals("@Research Bot (agent agent-123)", text.substring(refs[0].start, refs[0].end))
        assertEquals("@Ops (agent agent-local-9)", text.substring(refs[1].start, refs[1].end))
    }

    @Test
    fun aReferenceAtTheStartOfTheDraftParses() {
        val refs = AgentMentions.parse("@Ops (agent agent-9) hi")
        assertEquals(0, refs.single().start)
    }

    @Test
    fun anEmailAddressOrAPlainAtIsNotAReference() {
        assertTrue(AgentMentions.parse("mail me@host.com (agent agent-1)").isEmpty())
        assertTrue(AgentMentions.parse("@someone said hi").isEmpty())
    }

    @Test
    fun insertingThroughTheAutocompleteProducesAParsableReference() {
        val token = ComposerAutocomplete.activeToken("hey @res")!!
        val draft = ComposerAutocomplete.replaceToken("hey @res", token, "@${AgentMentions.mentionable(bot).insertText} ")
        assertEquals("hey @Research Bot (agent agent-123) ", draft)
        assertEquals("agent-123", AgentMentions.parse(draft).single().agentId)
        // The caret after the inserted reference is not inside an @ token any more.
        assertEquals(null, ComposerAutocomplete.activeToken(draft))
    }

    @Test
    fun removingAReferenceTakesItsTrailingSpaceAndNothingElse() {
        val text = "hey @Research Bot (agent agent-123) please"
        val ref = AgentMentions.parse(text).single()
        assertEquals("hey please", AgentMentions.remove(text, ref))
    }

    @Test
    fun removingAStaleSpanLeavesTheTextAlone() {
        val ref = AgentMentionRef("Ops", "agent-9", start = 40, end = 60)
        assertEquals("short", AgentMentions.remove("short", ref))
    }
}
