package com.letta.mobile.feature.chat.testutil

import com.letta.mobile.bot.clientmode.IClientModeController

class FakeClientModeController : IClientModeController {
    var ensureReadyCount: Int = 0

    override suspend fun ensureReady() {
        ensureReadyCount += 1
    }
}
