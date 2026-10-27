package com.letta.mobile.data.timeline

import com.letta.mobile.util.TelemetryValue

/** Reducer diagnostics use the same scalar-only boundary as other telemetry callers. */
internal typealias TelemetryAttribute = Pair<String, TelemetryValue>
