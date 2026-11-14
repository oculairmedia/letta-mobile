package com.letta.mobile.data.voice.endpoint

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/** The HTTP engine the speech endpoint client runs on, on Android and the desktop. */
object SpeechHttpEngines {
    /** A fresh CIO engine; whoever creates it closes it. */
    fun create(): HttpClientEngine = CIO.create()
}
