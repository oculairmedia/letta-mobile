package com.letta.mobile.data.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for letta-mobile-9e8zn: kotlinx-serialization-kotlinx-collections-immutable
 * integration. Validates [ImmutableListSerializer] correctly round-trips ImmutableList<T>
 * through JSON arrays via per-field annotation.
 */
class ImmutableListJsonTest {

    @Serializable
    private data class Strings(
        @Serializable(with = ImmutableListSerializer::class)
        val ids: ImmutableList<String>,
    )

    @Serializable
    private data class Ints(
        @Serializable(with = ImmutableListSerializer::class)
        val nums: ImmutableList<Int>,
    )

    @Test
    fun encodesImmutableListAsJsonArray() {
        val json = lettaJson()
        val out = json.encodeToString(Strings.serializer(), Strings(ids = persistentListOf("a", "b")))
        assertEquals("""{"ids":["a","b"]}""", out)
    }

    @Test
    fun decodesJsonArrayIntoImmutableList() {
        val json = lettaJson()
        val s = json.decodeFromString(Strings.serializer(), """{"ids":["a","b"]}""")
        assertEquals(persistentListOf("a", "b"), s.ids)
    }

    @Test
    fun encodesIntsAsJsonArray() {
        val json = lettaJson()
        val out = json.encodeToString(Ints.serializer(), Ints(nums = persistentListOf(1, 2)))
        assertEquals("""{"nums":[1,2]}""", out)
    }

    @Test
    fun decodesIntsAsJsonArray() {
        val json = lettaJson()
        val i = json.decodeFromString(Ints.serializer(), """{"nums":[1,2]}""")
        assertEquals(persistentListOf(1, 2), i.nums)
    }

    @Test
    fun preservesIdentityAcrossRoundTrip() {
        val json = lettaJson()
        val original = Strings(ids = persistentListOf("x"))
        val roundTripped = json.decodeFromString(
            Strings.serializer(),
            json.encodeToString(Strings.serializer(), original),
        )
        assertEquals(original, roundTripped)
    }

    @Test
    fun preservesCallerOverrides() {
        val json = lettaJson { prettyPrint = true }
        assertEquals(true, json.configuration.prettyPrint)
        assertEquals(true, json.configuration.ignoreUnknownKeys)
    }
}