package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.runtime.LocalRuntimeProviderConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLocalRuntimeProviderStoreTest {
    private fun tempBackendDir(): File = Files.createTempDirectory("local-runtime-provider-test").toFile()

    @Test
    fun readStatusForFreshDirectoryIsUnconfigured() {
        val backendDir = tempBackendDir()
        val store = DesktopLocalRuntimeProviderStore { backendDir }
        val status = store.readStatus()
        assertFalse(status.isConfigured)
        assertFalse(status.hasApiKey)
    }

    @Test
    fun saveWritesFileUnderProvidersSubdirectory() {
        val backendDir = tempBackendDir()
        val store = DesktopLocalRuntimeProviderStore { backendDir }
        val result = store.save(LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1", apiKey = "sk-test"))

        assertTrue(result.isSuccess)
        val authFile = File(File(backendDir, "providers"), "auth.json")
        assertTrue(authFile.isFile)
        assertTrue(authFile.readText().contains("lc-openai-compatible"))
    }

    @Test
    fun saveThenReadStatusRoundTripsWithoutExposingApiKey() {
        val backendDir = tempBackendDir()
        val store = DesktopLocalRuntimeProviderStore { backendDir }
        store.save(LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1", apiKey = "sk-test")).getOrThrow()

        val status = store.readStatus()
        assertEquals("http://localhost:8000/v1", status.baseUrl)
        assertTrue(status.hasApiKey)
    }

    @Test
    fun secondSavePreservesUnrelatedExistingProviders() {
        val backendDir = tempBackendDir()
        val authFile = File(File(backendDir, "providers").apply { mkdirs() }, "auth.json")
        authFile.writeText(
            """
            {
              "version": 1,
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
            """.trimIndent(),
        )
        val store = DesktopLocalRuntimeProviderStore { backendDir }

        store.save(LocalRuntimeProviderConfig(baseUrl = "https://proxy.example.com/v1")).getOrThrow()

        val contents = authFile.readText()
        assertTrue(contents.contains("\"ollama\""))
        assertTrue(contents.contains("http://localhost:11434/v1"))
    }

    @Test
    fun saveWithoutApiKeyClearsPreviouslyConfiguredKey() {
        val backendDir = tempBackendDir()
        val store = DesktopLocalRuntimeProviderStore { backendDir }
        store.save(LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1", apiKey = "sk-test")).getOrThrow()

        store.save(LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1", apiKey = null)).getOrThrow()

        assertFalse(store.readStatus().hasApiKey)
    }

    @Test
    fun saveFailureLeavesExistingFileUntouched() {
        val backendDir = tempBackendDir()
        val authFile = File(File(backendDir, "providers").apply { mkdirs() }, "auth.json")
        authFile.writeText("{ not valid json")

        val store = DesktopLocalRuntimeProviderStore { backendDir }
        val result = store.save(LocalRuntimeProviderConfig(baseUrl = "http://localhost:8000/v1"))

        assertTrue(result.isFailure)
        assertEquals("{ not valid json", authFile.readText())
    }
}
