package com.letta.mobile.data.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MemoryParitySectionReaderTest {
    @Test
    fun `failed section preserves successful sibling result`() = runTest {
        val warnings = mutableListOf<Pair<String, String>>()
        val reader = MemoryParitySectionReader { section, exceptionClass ->
            warnings += section to exceptionClass
        }

        val skills = reader.read("skills") { listOf("search") }
        val schedules = reader.read<List<String>>("schedules") { throw ProtocolFailure() }

        assertEquals(listOf("search"), skills.value)
        assertEquals(true, skills.loaded)
        assertEquals(null, schedules.value)
        assertEquals(false, schedules.loaded)
        assertIs<ProtocolFailure>(schedules.error)
        assertEquals(listOf("schedules" to "ProtocolFailure"), warnings)
    }

    @Test
    fun `healthy sections emit no telemetry`() = runTest {
        val warnings = mutableListOf<Pair<String, String>>()
        val reader = MemoryParitySectionReader { section, exceptionClass ->
            warnings += section to exceptionClass
        }

        reader.read("skills") { emptyList<String>() }
        reader.read("context") { 42 }

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `safe error mapping never exposes raw exception messages`() {
        val secret = "https://internal.example/token=super-secret"

        assertEquals("Memory data could not be loaded.", safeMemoryErrorMessage(RuntimeException(secret)))
        assertEquals(
            "Memory data is not available on this backend.",
            safeMemoryErrorMessage(UnsupportedOperationException(secret)),
        )
    }

    @Test
    fun `cancellation propagates without warning`() = runTest {
        val warnings = mutableListOf<Pair<String, String>>()
        val reader = MemoryParitySectionReader { section, exceptionClass ->
            warnings += section to exceptionClass
        }

        assertFailsWith<CancellationException> {
            reader.read("context") { throw CancellationException("cancel") }
        }
        assertEquals(emptyList(), warnings)
    }

    private class ProtocolFailure : RuntimeException("sensitive protocol body")
}
