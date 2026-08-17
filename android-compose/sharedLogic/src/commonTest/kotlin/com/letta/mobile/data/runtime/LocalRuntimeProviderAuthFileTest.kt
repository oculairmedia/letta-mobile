package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRuntimeProviderConfigTest {
    @Test
    fun acceptsHttpAndHttpsBaseUrls() {
        LocalRuntimeProviderConfig(baseUrl = "http://192.168.1.50:8000/v1")
        LocalRuntimeProviderConfig(baseUrl = "https://proxy.example.com/v1")
    }

    @Test
    fun rejectsBlankBaseUrl() {
        assertFailsWith<IllegalArgumentException> { LocalRuntimeProviderConfig(baseUrl = "") }
    }

    @Test
    fun rejectsBaseUrlWithoutScheme() {
        assertFailsWith<IllegalArgumentException> { LocalRuntimeProviderConfig(baseUrl = "localhost:11434/v1") }
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertFailsWith<IllegalArgumentException> { LocalRuntimeProviderConfig(baseUrl = "ftp://example.com") }
    }

    @Test
    fun rejectsSchemeWithNoHost() {
        assertFailsWith<IllegalArgumentException> { LocalRuntimeProviderConfig(baseUrl = "http://") }
    }
}

class LocalRuntimeProviderAuthFileTest {
    @Test
    fun writesFreshFileWhenNoneExists() {
        val result = mergeLocalRuntimeProviderAuth(
            existingJson = null,
            config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1", apiKey = "sk-local"),
            nowIso = "2026-08-16T00:00:00Z",
        )
        val status = readLocalRuntimeProviderStatus(result)
        assertEquals("http://localhost:8000/v1", status.baseUrl)
        assertTrue(status.hasApiKey)
        assertTrue(result.contains("\"lc-openai-compatible\""))
        assertTrue(result.contains("\"provider_type\": \"openai\""))
        assertTrue(result.contains("\"provider_category\": \"byok\""))
        assertTrue(result.contains("\"id\": \"local-provider-lc-openai-compatible\""))
        assertTrue(result.contains("\"version\": 1"))
    }

    @Test
    fun writesBlankExistingJsonAsFreshFile() {
        val result = mergeLocalRuntimeProviderAuth(
            existingJson = "   ",
            config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1"),
            nowIso = "2026-08-16T00:00:00Z",
        )
        assertEquals("http://localhost:8000/v1", readLocalRuntimeProviderStatus(result).baseUrl)
    }

    @Test
    fun noApiKeyWritesSentinelNotBlank() {
        val result = mergeLocalRuntimeProviderAuth(
            existingJson = null,
            config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:11434/v1", apiKey = null),
            nowIso = "2026-08-16T00:00:00Z",
        )
        assertTrue(result.contains("\"key\": \"not-needed\""))
        assertFalse(readLocalRuntimeProviderStatus(result).hasApiKey)
    }

    @Test
    fun blankApiKeyIsTreatedAsNoKey() {
        val result = mergeLocalRuntimeProviderAuth(
            existingJson = null,
            config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:11434/v1", apiKey = "   "),
            nowIso = "2026-08-16T00:00:00Z",
        )
        assertFalse(readLocalRuntimeProviderStatus(result).hasApiKey)
    }

    @Test
    fun preservesUnrelatedProvidersAndTopLevelFields() {
        val existing = """
            {
              "version": 1,
              "somethingLettaCodeAddsLater": true,
              "providers": {
                "ollama": {
                  "id": "local-provider-ollama",
                  "name": "ollama",
                  "provider_type": "ollama",
                  "provider_category": "byok",
                  "auth": { "type": "api", "key": "not-needed" },
                  "base_url": "http://localhost:11434/v1",
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-01T00:00:00Z"
                }
              }
            }
        """.trimIndent()

        val result = mergeLocalRuntimeProviderAuth(
            existingJson = existing,
            config = LocalRuntimeProviderConfig(baseUrl = "https://proxy.example.com/v1", apiKey = "sk-new"),
            nowIso = "2026-08-16T00:00:00Z",
        )

        assertTrue(result.contains("\"somethingLettaCodeAddsLater\": true"))
        assertTrue(result.contains("\"ollama\""))
        assertTrue(result.contains("http://localhost:11434/v1"))
        assertEquals("https://proxy.example.com/v1", readLocalRuntimeProviderStatus(result).baseUrl)
    }

    @Test
    fun preservesUnknownFieldsOnOwnProviderEntryAcrossUpdates() {
        val existing = """
            {
              "version": 1,
              "providers": {
                "lc-openai-compatible": {
                  "id": "local-provider-lc-openai-compatible",
                  "name": "lc-openai-compatible",
                  "provider_type": "openai",
                  "provider_category": "byok",
                  "auth": { "type": "api", "key": "sk-old" },
                  "base_url": "http://old-host:8000/v1",
                  "timeout": 45000,
                  "created_at": "2026-01-01T00:00:00Z",
                  "updated_at": "2026-01-01T00:00:00Z"
                }
              }
            }
        """.trimIndent()

        val result = mergeLocalRuntimeProviderAuth(
            existingJson = existing,
            config = LocalRuntimeProviderConfig(baseUrl = "http://new-host:8000/v1", apiKey = "sk-new"),
            nowIso = "2026-08-16T12:00:00Z",
        )

        assertTrue(result.contains("\"timeout\": 45000"))
        assertTrue(result.contains("\"created_at\": \"2026-01-01T00:00:00Z\""))
        assertTrue(result.contains("\"updated_at\": \"2026-08-16T12:00:00Z\""))
        assertEquals("http://new-host:8000/v1", readLocalRuntimeProviderStatus(result).baseUrl)
    }

    @Test
    fun secondUpdateKeepsOriginalCreatedAt() {
        val first = mergeLocalRuntimeProviderAuth(
            existingJson = null,
            config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1"),
            nowIso = "2026-01-01T00:00:00Z",
        )
        val second = mergeLocalRuntimeProviderAuth(
            existingJson = first,
            config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:9000/v1"),
            nowIso = "2026-02-01T00:00:00Z",
        )
        assertTrue(second.contains("\"created_at\": \"2026-01-01T00:00:00Z\""))
        assertTrue(second.contains("\"updated_at\": \"2026-02-01T00:00:00Z\""))
    }

    @Test
    fun corruptExistingJsonRefusesToMergeRatherThanDiscardingIt() {
        assertFailsWith<LocalRuntimeProviderAuthFileCorruptException> {
            mergeLocalRuntimeProviderAuth(
                existingJson = "{ not valid json",
                config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1"),
                nowIso = "2026-08-16T00:00:00Z",
            )
        }
    }

    @Test
    fun nonObjectJsonRootRefusesToMerge() {
        assertFailsWith<LocalRuntimeProviderAuthFileCorruptException> {
            mergeLocalRuntimeProviderAuth(
                existingJson = "[1, 2, 3]",
                config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1"),
                nowIso = "2026-08-16T00:00:00Z",
            )
        }
    }

    @Test
    fun statusReadForMissingFileIsUnconfigured() {
        val status = readLocalRuntimeProviderStatus(null)
        assertFalse(status.isConfigured)
        assertFalse(status.hasApiKey)
    }

    @Test
    fun statusReadForCorruptFileDegradesToUnconfiguredRatherThanThrowing() {
        val status = readLocalRuntimeProviderStatus("{ not valid json")
        assertFalse(status.isConfigured)
    }

    @Test
    fun customProviderNameParameterIsRespected() {
        val result = mergeLocalRuntimeProviderAuth(
            existingJson = null,
            config = LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1"),
            nowIso = "2026-08-16T00:00:00Z",
            providerName = "custom-name",
        )
        assertTrue(result.contains("\"custom-name\""))
        assertEquals(
            "http://localhost:8000/v1",
            readLocalRuntimeProviderStatus(result, providerName = "custom-name").baseUrl,
        )
    }
}
