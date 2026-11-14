package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetrySink
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr

/**
 * Voice mode's diagnostics on the app's [Telemetry], for the chat page to hand its voice mode: each
 * field becomes a telemetry attribute of its own kind (text, number, flag, or null). Voice mode only
 * ever reports states, reasons, ids, counts, levels and timings through it.
 */
val VoiceModeTelemetry: VoiceTelemetrySink = VoiceTelemetrySink { tag, name, fields ->
    Telemetry.event(tag, name, *Array(fields.size) { index -> fields[index].first attrOf fields[index].second })
}

private infix fun String.attrOf(value: Any?): Pair<String, TelemetryValue> = when (value) {
    null -> this attr (null as String?)
    is Boolean -> this attr value
    is Number -> this attr value
    is String -> this attr value
    else -> this attr value.toString()
}
