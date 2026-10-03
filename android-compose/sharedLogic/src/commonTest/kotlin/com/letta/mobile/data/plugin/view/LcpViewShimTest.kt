package com.letta.mobile.data.plugin.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The page shim: within its budget, well formed, and exposing the documented API (letta-mobile-s416w.31). */
class LcpViewShimTest {
    @Test
    fun theShimIsWithinItsSizeBudget() {
        val size = LcpViewShim.SOURCE.encodeToByteArray().size
        assertTrue(size <= LcpViewShim.MAX_BYTES, "the shim is $size bytes; the budget is ${LcpViewShim.MAX_BYTES}")
    }

    @Test
    fun itExposesTheDocumentedApi() {
        val api = LcpViewShim.SOURCE.substringAfter("w.lettaView={").let(::topLevelKeys)
        assertEquals(LcpViewShim.API, api)
        assertTrue("version:\"${LcpViewShim.VERSION}\"" in LcpViewShim.SOURCE)
        assertTrue(LcpViewShim.VERSION in LcpView.SUPPORTED_VIEW_VERSIONS)
    }

    @Test
    fun itSpeaksEveryViewMethodAndAcknowledgesTeardown() {
        ViewMethod.entries.forEach { assertTrue("\"${it.wire}\"" in LcpViewShim.SOURCE, it.wire) }
        assertTrue("\"${HostMethod.TEARDOWN}\"" in LcpViewShim.SOURCE)
        assertTrue("__lettaViewPost" in LcpViewShim.SOURCE && "__lettaViewReceive" in LcpViewShim.SOURCE)
        assertTrue("MessageEvent(\"message\"" in LcpViewShim.SOURCE)
    }

    @Test
    fun itsDelimitersBalance() {
        assertEquals("", unbalanced(LcpViewShim.SOURCE))
        assertTrue(LcpViewShim.SOURCE.endsWith("})(window);"))
    }

    @Test
    fun injectionQuotesThePageId() {
        assertTrue(LcpViewShim.inject("widget").startsWith("window.__lettaViewPage=\"widget\";(function(w)"))
        val hostile = LcpViewShim.inject("</script><script>alert(1)//\"")
        assertTrue("</script>" !in hostile)
        assertEquals("", unbalanced(hostile))
    }

    /** What is left open (or closed without an opener) in [source], skipping string literals. */
    private fun unbalanced(source: String): String {
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        val stack = ArrayDeque<Char>()
        var quote: Char? = null
        var escaped = false
        for (char in source) {
            when {
                quote != null -> {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == quote) quote = null
                }
                char == '"' || char == '\'' -> quote = char
                char in "([{" -> stack.addLast(char)
                char in pairs -> if (stack.removeLastOrNull() != pairs[char]) return "unexpected $char"
            }
        }
        return (stack.joinToString("") + (quote?.toString() ?: ""))
    }

    /** The keys of the object literal that [body] opens, at its top level. */
    private fun topLevelKeys(body: String): List<String> {
        val keys = mutableListOf<String>()
        var depth = 0
        var token = StringBuilder()
        var quote: Char? = null
        for (char in body) {
            if (quote != null) {
                if (char == quote) quote = null
                continue
            }
            when (char) {
                '"' -> quote = char
                '(', '[', '{' -> depth++
                ')', ']' -> depth--
                '}' -> if (depth == 0) return keys else depth--
                ':' -> if (depth == 0) keys += token.toString().trim().substringAfterLast(',')
                else -> if (depth == 0) token.append(char)
            }
            if (char == ',' && depth == 0) token = StringBuilder()
        }
        return keys
    }
}
