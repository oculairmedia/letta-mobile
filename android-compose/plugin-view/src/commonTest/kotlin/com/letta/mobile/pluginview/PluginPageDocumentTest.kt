package com.letta.mobile.pluginview

import com.letta.mobile.data.plugin.view.LcpViewShim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CSP meta, the channel script and the shim go in before every script of the page (letta-mobile-s416w.13). */
class PluginPageDocumentTest {
    private val spec = PluginViewFixtures.spec
    private val channel = "window.__lettaViewPost=function(){};"

    private fun injected(): String =
        "<meta http-equiv=\"Content-Security-Policy\"" // the meta opens what is injected

    @Test
    fun theInjectionOpensTheHead() {
        val document = PluginPageDocument.compose(PluginViewFixtures.WIDGET_HTML, spec, channel)
        val head = document.indexOf("<head>") + "<head>".length
        assertEquals(head, document.indexOf(injected()))
        assertTrue(document.indexOf(channel) < document.indexOf(LcpViewShim.SOURCE))
        assertTrue(document.indexOf(LcpViewShim.SOURCE) < document.indexOf("<title>"))
        assertTrue(document.startsWith("<!doctype html><html><head>"))
        assertTrue("window.__lettaViewPage=\"widget\";" in document)
    }

    @Test
    fun aPageWithoutAHeadGetsOneAfterHtmlOrTheDoctype() {
        val withHtml = PluginPageDocument.compose("<HTML lang=\"en\"><body>x</body></HTML>", spec, channel)
        assertTrue(withHtml.startsWith("<HTML lang=\"en\"><head>" + injected()), withHtml)

        val withDoctype = PluginPageDocument.compose("<!DOCTYPE html><body>x</body>", spec, channel)
        assertTrue(withDoctype.startsWith("<!DOCTYPE html><head>" + injected()), withDoctype)

        val bare = PluginPageDocument.compose("<p>x</p>", spec, channel)
        assertTrue(bare.startsWith("<head>" + injected()), bare)
        assertTrue(bare.endsWith("</head><p>x</p>"), bare)
    }

    @Test
    fun aHeadWithAttributesIsFoundButAHeaderIsNot() {
        val document = PluginPageDocument.compose("<html><header>h</header><head data-x=\"1\"></head></html>", spec, channel)
        assertTrue(document.contains("<head data-x=\"1\">" + injected()), document)
    }
}
