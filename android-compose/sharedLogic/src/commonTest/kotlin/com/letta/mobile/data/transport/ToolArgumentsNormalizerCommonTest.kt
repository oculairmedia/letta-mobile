package com.letta.mobile.data.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-s5vf9: one encoding layer comes off, and only when that is unambiguous. */
class ToolArgumentsNormalizerCommonTest {

    @Test
    fun doubleEncodedObjectLosesOneLayer() {
        // "{\"task_id\":\"bash_88\",\"block\":true}"  ->  {"task_id":"bash_88","block":true}
        val doubled = "\"{\\\"task_id\\\":\\\"bash_88\\\",\\\"block\\\":true}\""
        val once = "{\"task_id\":\"bash_88\",\"block\":true}"
        assertEquals(once, ToolArgumentsNormalizer.normalize(doubled))
    }

    @Test
    fun doubleEncodedArrayLosesOneLayer() {
        assertEquals("[1,2]", ToolArgumentsNormalizer.normalize("\"[1,2]\""))
    }

    @Test
    fun plainObjectIsUntouched() {
        val plain = "{\"command\":\"ls\",\"description\":\"list files\"}"
        assertEquals(plain, ToolArgumentsNormalizer.normalize(plain))
    }

    @Test
    fun aGenuineStringPayloadIsUntouched() {
        val quoted = "\"bash -lc ls\""
        assertEquals(quoted, ToolArgumentsNormalizer.normalize(quoted))
    }

    @Test
    fun aHalfAccumulatedStreamingFragmentIsUntouched() {
        val partial = "\"{\\\"command\\\":\\\"cat > /tmp"
        assertEquals(partial, ToolArgumentsNormalizer.normalize(partial))
    }

    @Test
    fun blankAndNullPassThrough() {
        assertEquals(null, ToolArgumentsNormalizer.normalize(null))
        assertEquals("", ToolArgumentsNormalizer.normalize(""))
    }

    @Test
    fun aStringWhoseValueIsAlsoAStringKeepsItsLayers() {
        // Unwrapping here would change what the payload means, so nothing is unwrapped.
        val nested = "\"\\\"inner\\\"\""
        assertEquals(nested, ToolArgumentsNormalizer.normalize(nested))
    }
}
