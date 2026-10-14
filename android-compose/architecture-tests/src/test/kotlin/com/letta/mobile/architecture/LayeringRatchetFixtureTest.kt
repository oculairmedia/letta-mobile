package com.letta.mobile.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LayeringRatchetFixtureTest {
    @Test
    fun `package graph reports only mutually importing pairs`() {
        val files = listOf(
            SourceFileImports("a.one", listOf("a.two.Thing", "kotlin.math.max")),
            SourceFileImports("a.two", listOf("a.one.Outer.Nested", "a.three.*")),
            SourceFileImports("a.three", listOf("a.three.Local")),
            SourceFileImports("a.four", listOf("a.one.helper")),
        )

        assertEquals(listOf("a.one <-> a.two"), PackageImportGraph.mutualPairs(files))
    }

    @Test
    fun `package graph resolves an import to the longest declared package`() {
        val files = listOf(
            SourceFileImports("a", listOf("a.b.C")),
            SourceFileImports("a.b", listOf("a.D")),
        )

        assertEquals(listOf("a <-> a.b"), PackageImportGraph.mutualPairs(files))
    }

    @Test
    fun `ratchet flags new and stale entries`() {
        val drift = RatchetBaseline.drift(listOf("kept", "new"), listOf("gone", "kept"), "x.txt")

        assertEquals(
            listOf("NEW, fix it rather than baselining it: new", "STALE, delete this line from x.txt: gone"),
            drift,
        )
    }

    @Test
    fun `ratchet requires a sorted unique baseline`() {
        assertEquals(
            listOf("x.txt must be sorted with one unique entry per line"),
            RatchetBaseline.drift(listOf("a", "b"), listOf("b", "a"), "x.txt"),
        )
        assertEquals(emptyList<String>(), RatchetBaseline.drift(listOf("a", "b"), listOf("a", "b"), "x.txt"))
    }
}
