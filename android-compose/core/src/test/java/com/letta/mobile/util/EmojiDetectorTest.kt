package com.letta.mobile.util

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class EmojiDetectorTest : WordSpec({
    "isEmojiOnly" should {
        "return true for a single emoji" {
            isEmojiOnly("😀").shouldBeTrue()
        }

        "return true for multiple emoji" {
            isEmojiOnly("😀😂🎉").shouldBeTrue()
        }

        "return true for emoji with spaces" {
            isEmojiOnly("😀 😂").shouldBeTrue()
        }

        "return false for text" {
            isEmojiOnly("hello").shouldBeFalse()
        }

        "return false for emoji mixed with text" {
            isEmojiOnly("hello 😀").shouldBeFalse()
        }

        "return false for empty string" {
            isEmojiOnly("").shouldBeFalse()
        }

        "return false for whitespace only" {
            isEmojiOnly("   ").shouldBeFalse()
        }
    }

    "emojiCount" should {
        "count a single emoji" {
            emojiCount("😀") shouldBe 1
        }

        "count three emoji" {
            emojiCount("😀😂🎉") shouldBe 3
        }

        "return zero for text" {
            emojiCount("hello") shouldBe 0
        }

        "return zero for empty string" {
            emojiCount("") shouldBe 0
        }
    }
})
