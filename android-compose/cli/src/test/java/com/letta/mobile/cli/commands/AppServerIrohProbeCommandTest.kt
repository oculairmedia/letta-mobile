package com.letta.mobile.cli.commands

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppServerIrohProbeCommandTest {
    @Test
    fun `probe text extraction handles structured content array`() {
        val delta = buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "hello ")
                    })
                    add(buildJsonObject { put("content", "world") })
                },
            )
        }

        assertEquals("hello world", delta.probeTextContent("content"))
    }

    @Test
    fun `probe text extraction handles structured content object`() {
        val delta = buildJsonObject {
            put(
                "content",
                buildJsonObject {
                    put("value", "object text")
                },
            )
        }

        assertEquals("object text", delta.probeTextContent("content"))
    }

    @Test
    fun `probe text extraction ignores non text objects`() {
        val delta = buildJsonObject {
            put("content", buildJsonObject { put("type", "image") })
        }

        assertEquals("", delta.probeTextContent("content"))
    }
}
