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
        val api = apiMembers(LcpViewShim.SOURCE)
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

    /** What is left open (or closed without an opener) in [source] once its string literals are removed. */
    private fun unbalanced(source: String): String {
        val code = STRING_LITERAL.replace(source, "\"\"")
        val stack = ArrayDeque<Char>()
        for (char in code.filter { it in OPENERS || it in CLOSERS }) {
            if (char in OPENERS) stack.addLast(char) else if (stack.removeLastOrNull() != OPENERS[CLOSERS.indexOf(char)]) return "unexpected $char"
        }
        return stack.joinToString("")
    }

    /** The members of the `w.lettaView={…}` literal, in order: each `name:` that follows `{` or `,` at its top level. */
    private fun apiMembers(source: String): List<String> {
        val literal = STRING_LITERAL.replace(source.substringAfter("w.lettaView=").substringBefore(";w.__lettaViewReceive"), "\"\"")
        return MEMBER.findAll(topLevel(literal)).map { it.groupValues[1] }.toList()
    }

    /** [literal] (one object literal) with every nested bracketed span removed, so only its own members remain. */
    private fun topLevel(literal: String): String {
        var text = literal.removePrefix("{").removeSuffix("}")
        while (NESTED.containsMatchIn(text)) text = NESTED.replace(text, "")
        return text
    }

    private companion object {
        const val OPENERS = "([{"
        const val CLOSERS = ")]}"
        val STRING_LITERAL = Regex("\"(\\\\.|[^\"\\\\])*\"")
        val NESTED = Regex("\\([^()\\[\\]{}]*\\)|\\[[^()\\[\\]{}]*\\]|\\{[^()\\[\\]{}]*\\}")
        val MEMBER = Regex("(?:^|,)([A-Za-z]+):")
    }
}