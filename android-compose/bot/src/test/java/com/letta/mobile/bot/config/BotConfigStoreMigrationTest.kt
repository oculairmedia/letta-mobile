package com.letta.mobile.bot.config

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-w2hx.4: regression coverage for the v1 → v2 in-place
 * migration of persisted [BotConfig] blobs. The interesting axes:
 *
 * 1. Heartbeat-enabled entry → `agent_id` is promoted to `heartbeat_agent_id`.
 * 2. Heartbeat-disabled entry → `agent_id` is dropped entirely (no
 *    server-side default agent exists in the new model).
 * 3. Mixed list — verify entries are migrated independently.
 * 4. Idempotence — re-running the migrator on a v2 blob is a no-op.
 */
@Tag("unit")
class BotConfigStoreMigrationTest : WordSpec({

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(raw: String): JsonArray = json.parseToJsonElement(raw) as JsonArray

    "migrateBotConfigBlob v1 → v2" should {
        "promote agent_id to heartbeat_agent_id when heartbeat is enabled" {
            val v1 = """
                [
                  {
                    "id": "bot-1",
                    "agent_id": "agent-abc",
                    "display_name": "My Bot",
                    "heartbeat_enabled": true
                  }
                ]
            """.trimIndent()

            val migrated = parse(migrateBotConfigBlob(v1, fromVersion = 1, toVersion = 2, json))
            val obj = migrated[0].jsonObject

            obj["agent_id"] shouldBe null
            obj["heartbeat_agent_id"]?.jsonPrimitive?.contentOrNull shouldBe "agent-abc"
            obj["heartbeat_enabled"]?.jsonPrimitive?.boolean shouldBe true
            obj["display_name"]?.jsonPrimitive?.contentOrNull shouldBe "My Bot"
        }

        "drop agent_id when heartbeat is disabled" {
            val v1 = """
                [
                  {
                    "id": "bot-2",
                    "agent_id": "agent-xyz",
                    "heartbeat_enabled": false
                  }
                ]
            """.trimIndent()

            val migrated = parse(migrateBotConfigBlob(v1, fromVersion = 1, toVersion = 2, json))
            val obj = migrated[0].jsonObject

            obj["agent_id"] shouldBe null
            obj["heartbeat_agent_id"] shouldBe null
        }

        "migrate entries independently in a mixed list" {
            val v1 = """
                [
                  { "id": "a", "agent_id": "a-1", "heartbeat_enabled": true },
                  { "id": "b", "agent_id": "b-1", "heartbeat_enabled": false },
                  { "id": "c", "agent_id": "c-1" }
                ]
            """.trimIndent()

            val migrated = parse(migrateBotConfigBlob(v1, fromVersion = 1, toVersion = 2, json))

            migrated[0].jsonObject["heartbeat_agent_id"]?.jsonPrimitive?.contentOrNull shouldBe "a-1"
            migrated[1].jsonObject["heartbeat_agent_id"] shouldBe null
            // No explicit `heartbeat_enabled` defaults to false, so agent_id is dropped.
            migrated[2].jsonObject["heartbeat_agent_id"] shouldBe null
            migrated.forEach { it.jsonObject["agent_id"] shouldBe null }
        }

        "leave an existing heartbeat_agent_id untouched" {
            val v1 = """
                [
                  {
                    "id": "bot-1",
                    "agent_id": "stale-agent",
                    "heartbeat_agent_id": "preserved-agent",
                    "heartbeat_enabled": true
                  }
                ]
            """.trimIndent()

            val migrated = parse(migrateBotConfigBlob(v1, fromVersion = 1, toVersion = 2, json))
            val obj = migrated[0].jsonObject

            obj["agent_id"] shouldBe null
            obj["heartbeat_agent_id"]?.jsonPrimitive?.contentOrNull shouldBe "preserved-agent"
        }

        "be idempotent when fromVersion == toVersion" {
            val v2 = """[ { "id": "bot-1", "heartbeat_agent_id": "agent-abc" } ]"""

            val migrated = migrateBotConfigBlob(v2, fromVersion = 2, toVersion = 2, json)
            migrated shouldBe v2
        }
    }
})
