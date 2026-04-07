package com.letta.mobile.domain

import com.letta.mobile.testutil.TestData
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty

class AgentSearchTest : WordSpec({
    val search = AgentSearch()
    val agents = listOf(
        TestData.agent(id = "1", name = "General Assistant", model = "letta/letta-free", tags = listOf("default", "chat"), description = "A general purpose agent"),
        TestData.agent(id = "2", name = "Code Helper", model = "openai/gpt-4o", tags = listOf("code", "programming"), description = "Helps with code"),
        TestData.agent(id = "3", name = "Research Bot", model = "anthropic/claude-3.5-sonnet", tags = listOf("research"), description = "Does research"),
        TestData.agent(id = "4", name = "Writer", model = "openai/gpt-4o", tags = listOf("writing"), description = "Creative writing assistant"),
    )

    "search" should {
        "return all agents for empty query" {
            search.search(agents, "") shouldHaveSize agents.size
        }

        "return all agents for blank query" {
            search.search(agents, "   ") shouldHaveSize agents.size
        }

        "return exact name match first" {
            search.search(agents, "Code Helper").first().name shouldBe "Code Helper"
        }

        "return partial name match" {
            search.search(agents, "General").first().name shouldBe "General Assistant"
        }

        "be case insensitive" {
            search.search(agents, "code helper").first().name shouldBe "Code Helper"
        }

        "match tags" {
            search.search(agents, "programming").any { it.name == "Code Helper" } shouldBe true
        }

        "match model names" {
            search.search(agents, "gpt-4o").size shouldBe 2
        }

        "return empty when nothing matches" {
            search.search(agents, "zzzznonexistent").shouldBeEmpty()
        }

        "return empty for empty agent list" {
            search.search(emptyList(), "test").shouldBeEmpty()
        }

        "sort results by relevance" {
            search.search(agents, "Code").first().name shouldBe "Code Helper"
        }
    }
})
