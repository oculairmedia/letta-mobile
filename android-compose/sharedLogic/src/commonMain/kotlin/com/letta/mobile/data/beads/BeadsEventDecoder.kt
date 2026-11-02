package com.letta.mobile.data.beads

internal object BeadsEventDecoder {
    fun decode(event: String?, data: String): BeadsEvent {
        if (event != null && event != "message") throw BeadsProtocolException()
        val value = try {
            wireJson.parseToJsonElement(data)
        } catch (_: Exception) {
            throw BeadsProtocolException()
        }
        return decodeBeads(value)
    }
}
