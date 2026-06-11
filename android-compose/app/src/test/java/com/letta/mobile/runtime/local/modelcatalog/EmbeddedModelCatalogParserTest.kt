package com.letta.mobile.runtime.local.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class EmbeddedModelCatalogParserTest {
    @Test
    fun parse_acceptsGalleryStyleFields() {
        val entries = EmbeddedModelCatalogParser().parse(
            """
            [
              {
                "name": "Gemma LiteRT-LM",
                "modelId": "google/gemma-litert-lm",
                "modelFile": "gemma.litertlm",
                "sizeInBytes": 1024,
                "estimatedPeakMemoryInBytes": 2048,
                "defaultConfig": { "maxTokens": 4096, "accelerators": ["cpu", "gpu"] },
                "taskTypes": ["chat"]
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals("Gemma LiteRT-LM", entries.single().name)
        assertEquals("google/gemma-litert-lm", entries.single().modelId)
        assertEquals("gemma.litertlm", entries.single().modelFile)
        assertEquals(4096, entries.single().defaultConfig.maxTokens)
        assertFalse(entries.single().requiresAuth)
        assertTrue(entries.single().isSupported)
    }

    @Test
    fun parse_readsRequiresAuthMetadata() {
        val entries = EmbeddedModelCatalogParser().parse(
            """
            [
              {
                "name": "Gated Gemma LiteRT-LM",
                "modelId": "google/gated",
                "modelFile": "gated.litertlm",
                "sizeInBytes": 1024,
                "estimatedPeakMemoryInBytes": 2048,
                "defaultConfig": { "maxTokens": 4096, "accelerators": ["cpu"] },
                "taskTypes": ["chat"],
                "requiresAuth": true
              }
            ]
            """.trimIndent()
        )

        assertTrue(entries.single().requiresAuth)
    }

    @Test
    fun supportedEmbeddedModels_filtersTaskModelsAsUnsupported() {
        val entries = EmbeddedModelCatalogParser().parse(
            """
            [
              {
                "name": "Runnable LiteRT-LM",
                "modelId": "google/runnable",
                "modelFile": "runnable.litertlm",
                "sizeInBytes": 1024,
                "estimatedPeakMemoryInBytes": 2048,
                "defaultConfig": { "maxTokens": 4096, "accelerators": ["cpu"] },
                "taskTypes": ["chat"]
              },
              {
                "name": "Gallery task sample",
                "modelId": "google/task-sample",
                "modelFile": "sample.task",
                "sizeInBytes": 1024,
                "estimatedPeakMemoryInBytes": 2048,
                "defaultConfig": { "maxTokens": 4096, "accelerators": ["cpu"] },
                "taskTypes": ["chat"],
                "supported": false,
                "unsupportedReason": "Needs .task adapter"
              }
            ]
            """.trimIndent()
        )

        assertEquals(listOf("Runnable LiteRT-LM"), entries.supportedEmbeddedModels().map { it.name })
        assertFalse(entries.last().isSupported)
        assertEquals("Needs .task adapter", entries.last().unsupportedReason)
    }
}
