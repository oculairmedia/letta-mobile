package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.UiSubagentNotification

internal fun extractSubagentNotification(raw: String): UiSubagentNotification? {
    return com.letta.mobile.data.chat.projection.extractSubagentNotification(raw)
}
