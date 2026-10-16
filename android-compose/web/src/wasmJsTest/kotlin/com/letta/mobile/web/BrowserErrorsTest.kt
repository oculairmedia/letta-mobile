package com.letta.mobile.web

import com.letta.mobile.web.data.cleanBrowserErrorMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserErrorsTest {
    @Test
    fun aJsErrorReadsAsItsMessage() {
        assertEquals(
            "invalid Iroh endpoint id: invalid length",
            cleanBrowserErrorMessage("Non-Kotlin exception Error: invalid Iroh endpoint id: invalid length of type 'class Error'"),
        )
        assertEquals("Connection closed", cleanBrowserErrorMessage("Connection closed"))
    }
}
