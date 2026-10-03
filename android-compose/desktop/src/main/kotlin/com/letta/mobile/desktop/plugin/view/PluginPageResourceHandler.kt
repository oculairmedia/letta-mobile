package com.letta.mobile.desktop.plugin.view

import kotlinx.coroutines.Job
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

/**
 * Answers one `letta-plugin` request from the [PluginPageServer]: the page is read off the
 * browser's IO thread (it may come over the network), then its status, headers and bytes are
 * handed to CEF.
 */
internal class PluginPageResourceHandler(
    private val server: PluginPageServer,
    private val work: PluginViewWork,
) : CefResourceHandlerAdapter() {
    @Volatile
    private var response: PluginPageResponse? = null
    private var body = PluginPageBody(ByteArray(0))
    private var job: Job? = null

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        val url = request.url
        job = work.launch {
            val answer = server.respond(url)
            body = PluginPageBody(answer.body)
            response = answer
            callback.Continue()
        }
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        val answer = this.response ?: return
        response.status = answer.status
        response.statusText = answer.statusText
        response.mimeType = answer.mimeType
        response.setHeaderMap(answer.headers + ("Content-Type" to "${answer.mimeType}; charset=utf-8"))
        responseLength.set(answer.body.size)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        val count = body.readInto(dataOut, bytesToRead)
        bytesRead.set(count)
        return count > 0
    }

    override fun cancel() {
        job?.cancel()
    }
}

/** A response body read out in the chunks CEF asks for. */
internal class PluginPageBody(private val bytes: ByteArray) {
    private var offset = 0

    /** Copies up to [max] of the bytes not yet read into [out]; 0 once everything was read. */
    fun readInto(out: ByteArray, max: Int): Int {
        val count = minOf(max, out.size, bytes.size - offset)
        if (count <= 0) return 0
        bytes.copyInto(out, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
        offset += count
        return count
    }
}
