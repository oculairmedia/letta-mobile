package com.letta.mobile.util

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import java.time.Instant

class RelativeTimeFormatterTest : WordSpec({
    "formatRelativeTime" should {
        "return empty for null" {
            formatRelativeTime(null) shouldBe ""
        }

        "return empty for blank" {
            formatRelativeTime("") shouldBe ""
        }

        "return Just now for recent timestamp" {
            val recent = Instant.now().minusSeconds(10).toString()
            formatRelativeTime(recent) shouldBe "Just now"
        }

        "return minutes ago for <1h" {
            val minutesAgo = Instant.now().minusSeconds(600).toString()
            formatRelativeTime(minutesAgo) shouldContain "m ago"
        }

        "return hours ago for <24h" {
            val hoursAgo = Instant.now().minusSeconds(7200).toString()
            formatRelativeTime(hoursAgo) shouldContain "h ago"
        }

        "return Yesterday for 24-48h" {
            val yesterday = Instant.now().minusSeconds(86400).toString()
            formatRelativeTime(yesterday) shouldBe "Yesterday"
        }

        "return days ago for <7d" {
            val daysAgo = Instant.now().minusSeconds(259200).toString()
            formatRelativeTime(daysAgo) shouldContain "d ago"
        }

        "return date for older timestamps" {
            formatRelativeTime("2023-01-15T10:00:00Z").shouldNotBeBlank()
        }

        "return raw string for unparseable input" {
            formatRelativeTime("not a date") shouldBe "not a date"
        }
    }
})
