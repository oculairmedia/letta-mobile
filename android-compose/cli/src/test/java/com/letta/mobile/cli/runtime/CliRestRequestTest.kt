package com.letta.mobile.cli.runtime

import com.github.ajalt.clikt.core.UsageError
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CliRestRequestTest {
    @Test
    fun `buildRestUrl normalizes base path and query params`() {
        val url = buildRestUrl(
            baseUrl = "https://api.example.com/",
            path = "/v1/agents",
            queryParams = parseQueryParams(listOf("limit=20", "name=hello world")),
        )

        assertEquals("https://api.example.com/v1/agents?limit=20&name=hello%20world", url)
    }

    @Test
    fun `parseQueryParams rejects malformed values`() {
        assertThrows(UsageError::class.java) {
            parseQueryParams(listOf("missing-delimiter"))
        }
        assertThrows(UsageError::class.java) {
            parseQueryParams(listOf("=missing-name"))
        }
    }

    @Test
    fun `resolveRequestBody accepts inline body`() {
        assertEquals("""{"name":"cli"}""", resolveRequestBody("""{"name":"cli"}""", null))
    }

    @Test
    fun `resolveRequestBody reads body file`() {
        val file = Files.createTempFile("cli-rest-request", ".json")
        try {
            Files.write(file, """{"name":"from-file"}""".toByteArray(Charsets.UTF_8))
            assertEquals("""{"name":"from-file"}""", resolveRequestBody(null, file.toString()))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `resolveRequestBody rejects inline and file body together`() {
        assertThrows(UsageError::class.java) {
            resolveRequestBody("{}", "body.json")
        }
    }

    @Test
    fun `resolveRequestBody wraps body file read failures as usage errors`() {
        val missingFile = Files.createTempDirectory("cli-rest-request").resolve("missing.json")

        assertThrows(UsageError::class.java) {
            resolveRequestBody(null, missingFile.toString())
        }
    }
}
