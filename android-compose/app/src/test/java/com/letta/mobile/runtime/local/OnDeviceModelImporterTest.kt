package com.letta.mobile.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class OnDeviceModelImporterTest {
    @Test
    fun sanitizeOnDeviceModelFileName_removesPathAndUnsafeCharacters() {
        assertEquals(
            "Gemma_3n_E4B.litertlm",
            sanitizeOnDeviceModelFileName("/downloads/Gemma 3n E4B.litertlm"),
        )
    }

    @Test
    fun sanitizeOnDeviceModelFileName_fallsBackForBlankNames() {
        assertEquals("model.litertlm", sanitizeOnDeviceModelFileName("   "))
    }

    @Test
    fun onDeviceModelHandleFor_buildsLocalHandleFromFileName() {
        assertEquals("local/gemma-3n-e4b", onDeviceModelHandleFor("Gemma_3n E4B.litertlm"))
    }
}
