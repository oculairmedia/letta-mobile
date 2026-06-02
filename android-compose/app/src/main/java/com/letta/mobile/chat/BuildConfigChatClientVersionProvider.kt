package com.letta.mobile.chat

import com.letta.mobile.BuildConfig
import com.letta.mobile.feature.chat.coordination.ChatClientVersionProvider
import javax.inject.Inject

class BuildConfigChatClientVersionProvider @Inject constructor() : ChatClientVersionProvider {
    override val clientVersion: String = "letta-mobile/${BuildConfig.VERSION_NAME} (android)"
}
