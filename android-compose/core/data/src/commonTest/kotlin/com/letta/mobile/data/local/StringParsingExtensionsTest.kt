package com.letta.mobile.data.local

import kotlin.test.assertEquals
import kotlin.test.Test

class StringParsingExtensionsTest {
    @Test
    fun testSplitTrimAndFilter() {
        assertEquals(listOf("tag1", "tag2", "tag3", "tag4", "tag5"), " tag1 , tag2, ,tag3,   tag4   , tag5 ".splitTrimAndFilter(","))
        assertEquals(listOf("att1", "att2", "att3", "att4", "att5"), " att1 || att2|| ||att3||   att4   || att5 ".splitTrimAndFilter("||"))
        assertEquals(emptyList<String>(), "".splitTrimAndFilter(","))
        assertEquals(emptyList<String>(), "   ".splitTrimAndFilter(","))
        assertEquals(emptyList<String>(), " , , ".splitTrimAndFilter(","))
    }
}
