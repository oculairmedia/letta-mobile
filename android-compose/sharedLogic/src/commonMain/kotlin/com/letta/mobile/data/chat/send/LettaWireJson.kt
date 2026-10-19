package com.letta.mobile.data.chat.send

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.Json

/**
 * Lenient decode config for Letta wire payloads, shared by the send-path
 * decoder ([OutboundMessageCreate]) and the iroh admin_rpc gateway.
 *
 * Alias of [LettaJson.adminRpc]. It is not [LettaJson.wire]: these
 * payloads keep `isLenient` and `coerceInputValues`, which App Server frames do not use.
 */
internal val lettaWireJson: Json = LettaJson.adminRpc
