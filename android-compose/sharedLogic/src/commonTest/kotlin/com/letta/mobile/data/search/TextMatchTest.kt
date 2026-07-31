package com.letta.mobile.data.search

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class TextMatchTest {
    @Test
    fun testEmptyOrBlankQueryReturnsTrue() {
        assertTrue(TextMatch.matches(""))
        assertTrue(TextMatch.matches("   ", "field1"))
    }

    @Test
    fun testCaseInsensitiveMatching() {
        assertTrue(TextMatch.matches("abc", "ABCDEF"))
        assertTrue(TextMatch.matches("XyZ", "123xYz456"))
    }

    @Test
    fun unicodeCaseVariantsRemainSearchable() {
        assertTrue(TextMatch.matches("Σ", "ς"))
        assertTrue(TextMatch.matches("I", "ı"))
    }

    @Test
    fun testPartialSubstringMatching() {
        assertTrue(TextMatch.matches("def", "abcdefghi"))
    }

    @Test
    fun testMatchesAgainstAnyFieldIncludingNulls() {
        assertTrue(TextMatch.matches("test", null, "other", "this is a test string"))
        assertFalse(TextMatch.matches("test", null, "other", "nope"))
    }

    @Test
    fun testNoMatchReturnsFalse() {
        assertFalse(TextMatch.matches("apple", "banana", "cherry"))
    }

    @Test
    fun testQueryIsTrimmedBeforeMatching() {
        assertTrue(TextMatch.matches("  def  ", "abcdefghi"))
    }

    @Test
    fun spacedQueryFindsPunctuatedName() {
        // The reported defect: an agent named "PM - letta-mobile" was
        // unfindable by typing its words, because the old whole-query
        // `contains` required the separator and hyphen to be typed literally.
        assertTrue(TextMatch.matches("pm letta mobile", "PM - letta-mobile"))
        assertTrue(TextMatch.matches("PM letta mobile", "PM - letta-mobile"))
        // Sibling agents that differ only in the trailing token stay distinct.
        assertFalse(TextMatch.matches("pm letta mobile", "PM - letta-code"))
        assertFalse(TextMatch.matches("pm letta mobile", "PM - vibesync"))
    }

    @Test
    fun punctuationInQueryStillMatches() {
        // Typing the exact punctuated name must keep working.
        assertTrue(TextMatch.matches("PM - letta-mobile", "PM - letta-mobile"))
        assertTrue(TextMatch.matches("letta-mobile", "PM - letta-mobile"))
        assertTrue(TextMatch.matches("letta mobile", "PM - letta-mobile"))
    }

    @Test
    fun tokensMayMatchAcrossSeparateFields() {
        // Palette rows carry label + sublabel; a query spanning both should hit.
        assertTrue(TextMatch.matches("letta agent", "PM - letta-mobile", "agent"))
        assertFalse(TextMatch.matches("letta destination", "PM - letta-mobile", "agent"))
    }

    @Test
    fun testMatchRanking() {
        // While TextMatch.matches just returns a boolean, the common search
        // primitive logic uses it in filters where the list order or count
        // is maintained externally. We can test that sorting multiple items
        // based on a match works correctly as an integration behavior.
        val items = listOf("Charlie", "Bob Builder", "Alice", "bobby", "Zebra")
        val query = "bob"
        val matchedItems = items.filter { TextMatch.matches(query, it) }
        
        assertEquals(2, matchedItems.size)
        // Ensure both match regardless of capitalization/partial match
        assertTrue(matchedItems.contains("Bob Builder"))
        assertTrue(matchedItems.contains("bobby"))
        
        // Simulating ranking: precise match vs substring
        val ranked = matchedItems.sortedBy { 
            if (it.equals(query, ignoreCase = true)) 0 else 1 
        }
        // Since we are sorting, we just want to ensure that "bobby" and "Bob Builder"
        // remain in the correct rank relative to what the user requested, but wait
        // neither of those is an exact match for "bob", because length is different!
        // "bobby" is not "bob". So they both get rank 1.
        
        // Let's add an exact match to the list and test actual ranking.
        val itemsWithExact = listOf("Bob Builder", "bob", "bobby")
        val matchedExactItems = itemsWithExact.filter { TextMatch.matches(query, it) }
        val rankedExact = matchedExactItems.sortedBy { 
            if (it.equals(query, ignoreCase = true)) 0 else 1 
        }
        
        assertEquals("bob", rankedExact[0])
        assertTrue(rankedExact[1] == "Bob Builder" || rankedExact[1] == "bobby")
    }
}
