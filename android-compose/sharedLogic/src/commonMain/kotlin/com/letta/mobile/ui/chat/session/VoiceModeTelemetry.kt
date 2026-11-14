package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetrySink
import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetryValue
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr

/**
 * Voice mode's diagnostics on the app's [Telemetry], for the chat page to hand its voice mode: each
 * field becomes a telemetry attribute of its own kind (text, number, flag, or none). Voice mode only
 * ever reports states, reasons, ids, counts, levels and timings through it.
 */
@Suppress("SpreadOperator") // Telemetry.event takes varargs; the fields arrive as a list, so one array is built.
val VoiceModeTelemetry: VoiceTelemetrySink = VoiceTelemetrySink { tag, name, fields ->
    Telemetry.event(tag, name, *Array(fields.size) { index -> fields[index].first attrOf fields[index].second })
}

private infix fun String.attrOf(value: VoiceTelemetryValue): Pair<String, TelemetryValue> = when (value) {
    is VoiceTelemetryValue.Text -> this attr value.value
    is VoiceTelemetryValue.Numeric -> this attr value.value
    is VoiceTelemetryValue.Flag -> this attr value.value
    VoiceTelemetryValue.Missing -> this attr (null as String?)
}
