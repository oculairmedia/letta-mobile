package com.letta.mobile.ui.screens.config

import com.letta.mobile.data.api.AppServerConnectionTester
import com.letta.mobile.data.transport.appserver.AppServerProbeResult

/** Records the (url, token) pairs a connection test was asked to probe and answers from [next]. */
internal class FakeAppServerConnectionTester : AppServerConnectionTester() {
    val calls = mutableListOf<Pair<String, String?>>()
    var next: suspend () -> AppServerProbeResult? = { null }

    override suspend fun test(serverUrl: String, accessToken: String?): AppServerProbeResult? {
        calls += serverUrl to accessToken
        return next()
    }
}
