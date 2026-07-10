package com.letta.mobile.data.chat.send

import kotlinx.serialization.json.Json

/**
 * Lenient decode config for Letta wire payloads, shared by the send-path
 * decoder ([OutboundMessageCreate]) and the iroh admin_rpc gateway.
 */
internal val lettaWireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}
