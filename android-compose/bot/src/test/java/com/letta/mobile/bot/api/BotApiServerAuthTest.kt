package com.letta.mobile.bot.api

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe

class BotApiServerAuthTest : WordSpec({
    "isAuthorizedHeader" should {
        "allow requests when auth is disabled" {
            isAuthorizedHeader(authHeader = null, authToken = null) shouldBe true
            isAuthorizedHeader(authHeader = "Bearer ignored", authToken = "") shouldBe true
        }

        "accept matching bearer tokens" {
            isAuthorizedHeader(
                authHeader = "Bearer secret-token",
                authToken = "secret-token",
            ) shouldBe true
        }

        "reject missing or mismatched bearer tokens" {
            isAuthorizedHeader(authHeader = null, authToken = "secret-token") shouldBe false
            isAuthorizedHeader(authHeader = "Bearer wrong", authToken = "secret-token") shouldBe false
            isAuthorizedHeader(authHeader = "Basic abc123", authToken = "secret-token") shouldBe false
        }
    }

    "extractBearerToken" should {
        "parse bearer token values case-insensitively" {
            extractBearerToken("Bearer secret-token") shouldBe "secret-token"
            extractBearerToken("bearer secret-token") shouldBe "secret-token"
        }

        "ignore blank or unsupported auth headers" {
            extractBearerToken(null) shouldBe null
            extractBearerToken("") shouldBe null
            extractBearerToken("Basic abc123") shouldBe null
        }
    }
})
