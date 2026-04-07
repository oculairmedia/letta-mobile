package com.letta.mobile.ui.common

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class TimelineGrouperTest : WordSpec({
    data class FakeMessage(val id: String, val role: String, val ts: String = "")

    fun group(messages: List<FakeMessage>): List<Pair<FakeMessage, GroupPosition>> =
        groupMessages(messages, getRole = { it.role }, getTimestamp = { it.ts })

    "groupMessages" should {
        "return empty for empty list" {
            group(emptyList()).shouldBeEmpty()
        }

        "return None for a single message" {
            val result = group(listOf(FakeMessage("1", "user")))
            result.size shouldBe 1
            result[0].second shouldBe GroupPosition.None
        }

        "mark two same-role messages as First and Last" {
            val result = group(listOf(FakeMessage("1", "user"), FakeMessage("2", "user")))
            result[0].second shouldBe GroupPosition.First
            result[1].second shouldBe GroupPosition.Last
        }

        "mark two different-role messages as None and None" {
            val result = group(listOf(FakeMessage("1", "user"), FakeMessage("2", "assistant")))
            result[0].second shouldBe GroupPosition.None
            result[1].second shouldBe GroupPosition.None
        }

        "mark three same-role messages as First Middle Last" {
            val result = group(
                listOf(
                    FakeMessage("1", "assistant"),
                    FakeMessage("2", "assistant"),
                    FakeMessage("3", "assistant"),
                )
            )
            result[0].second shouldBe GroupPosition.First
            result[1].second shouldBe GroupPosition.Middle
            result[2].second shouldBe GroupPosition.Last
        }

        "mark alternating roles as all None" {
            val result = group(
                listOf(
                    FakeMessage("1", "user"),
                    FakeMessage("2", "assistant"),
                    FakeMessage("3", "user"),
                    FakeMessage("4", "assistant"),
                )
            )
            result.forEach { (_, position) -> position shouldBe GroupPosition.None }
        }

        "assign correct positions for mixed groups" {
            val result = group(
                listOf(
                    FakeMessage("1", "user"),
                    FakeMessage("2", "user"),
                    FakeMessage("3", "assistant"),
                    FakeMessage("4", "assistant"),
                    FakeMessage("5", "assistant"),
                    FakeMessage("6", "user"),
                )
            )
            result[0].second shouldBe GroupPosition.First
            result[1].second shouldBe GroupPosition.Last
            result[2].second shouldBe GroupPosition.First
            result[3].second shouldBe GroupPosition.Middle
            result[4].second shouldBe GroupPosition.Last
            result[5].second shouldBe GroupPosition.None
        }

        "break groups on tool messages" {
            val result = group(
                listOf(
                    FakeMessage("1", "assistant"),
                    FakeMessage("2", "tool"),
                    FakeMessage("3", "assistant"),
                )
            )
            result[0].second shouldBe GroupPosition.None
            result[1].second shouldBe GroupPosition.None
            result[2].second shouldBe GroupPosition.None
        }

        "preserve original message objects" {
            val result = group(listOf(FakeMessage("a", "user"), FakeMessage("b", "user")))
            result[0].first.id shouldBe "a"
            result[1].first.id shouldBe "b"
        }
    }
})
