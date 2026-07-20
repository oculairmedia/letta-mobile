package com.letta.mobile.runtime.actions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object DeviceActionCommandCatalog {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun toJson(): String = json.encodeToString(catalog)

    val catalog = DeviceActionCatalog(
        version = 1,
        commands = listOf(
            DeviceActionCommandDescriptor(
                command = "device.catalog",
                summary = "List supported device_action commands and compact input hints.",
                riskTier = "low",
                executionMode = "read_only",
                input = "none",
                example = "{\"command\":\"device.catalog\"}",
            ),
            DeviceActionCommandDescriptor(
                command = "sensors.summary",
                summary = "Return compact no-permission device context: battery, thermal, memory, storage, network, sensor count, gated status.",
                riskTier = "low",
                executionMode = "read_only",
                input = "none",
                example = "{\"command\":\"sensors.summary\"}",
            ),
            DeviceActionCommandDescriptor(
                command = "sensors.catalog",
                summary = "Return bounded Android SensorManager catalog; optional query and limit filter descriptors.",
                riskTier = "low",
                executionMode = "read_only",
                input = "optional: {query?: string, limit?: number}",
                example = "{\"command\":\"sensors.catalog\",\"input\":{\"query\":\"accelerometer\",\"limit\":8}}",
            ),
            DeviceActionCommandDescriptor(
                command = "sensors.sample",
                summary = "Read live values from one matched Android SensorManager sensor; optional query, samples, and timeout.",
                riskTier = "low",
                executionMode = "read_only",
                input = "required: {query: string}; optional: {samples?: number, timeoutMs?: number}",
                example = "{\"command\":\"sensors.sample\",\"input\":{\"query\":\"MLX90632\",\"samples\":1,\"timeoutMs\":2000}}",
            ),
            DeviceActionCommandDescriptor(
                command = "sensors.snapshot",
                summary = "Return bounded typed snapshot with no-permission telemetry and sensor descriptors.",
                riskTier = "low",
                executionMode = "read_only",
                input = "optional: {limit?: number}",
                example = "{\"command\":\"sensors.snapshot\",\"input\":{\"limit\":16}}",
            ),
            DeviceActionCommandDescriptor(
                command = "mobile.capabilities",
                summary = "Return Android mobile-action capability matrix and placeholder privileged tiers.",
                riskTier = "low",
                executionMode = "read_only",
                input = "none",
                example = "{\"command\":\"mobile.capabilities\"}",
            ),
            DeviceActionCommandDescriptor(
                command = "intent.dry_run",
                summary = "Resolve a user-mediated Android intent action without launching it.",
                riskTier = "low",
                executionMode = "user_mediated_dry_run",
                input = "required: {tool: open_wifi_settings|show_location_on_map|compose_email|insert_contact|insert_calendar_event, ...toolFields}",
                example = "{\"command\":\"intent.dry_run\",\"input\":{\"tool\":\"compose_email\",\"to\":\"ada@example.com\"}}",
            ),
            DeviceActionCommandDescriptor(
                command = "intent.execute",
                summary = "Open a user-mediated Android UI intent. The user must confirm send/save/settings changes; the app never completes them silently.",
                riskTier = "medium",
                executionMode = "user_mediated_ui",
                input = "required: {tool: open_wifi_settings|show_location_on_map|compose_email|insert_contact|insert_calendar_event, ...toolFields}; returns opened UI awaiting user confirmation",
                example = "{\"command\":\"intent.execute\",\"input\":{\"tool\":\"compose_email\",\"to\":\"ada@example.com\",\"subject\":\"Hello\"}}",
            ),
            DeviceActionCommandDescriptor(
                command = "hardware.capabilities",
                summary = "Return hardware-control capability status for flashlight, vibration, and audio volume.",
                riskTier = "low",
                executionMode = "read_only",
                input = "none",
                example = "{\"command\":\"hardware.capabilities\"}",
            ),
            DeviceActionCommandDescriptor(
                command = "hardware.flashlight",
                summary = "Enable or disable the Android flashlight/torch when available.",
                riskTier = "medium",
                executionMode = "direct",
                input = "required: {enabled: boolean}; optional: {dryRun?: boolean}",
                example = "{\"command\":\"hardware.flashlight\",\"input\":{\"enabled\":true}}",
            ),
            DeviceActionCommandDescriptor(
                command = "hardware.flashlight_probe",
                summary = "Probe flashlight/torch control support without changing torch state.",
                riskTier = "low",
                executionMode = "dry_run",
                input = "optional: {enabled?: boolean, dryRun?: true}; dryRun is forced true by default",
                example = "{\"command\":\"hardware.flashlight_probe\"}",
            ),
            DeviceActionCommandDescriptor(
                command = "hardware.vibrate",
                summary = "Trigger a safe clamped vibration pattern or duration where Android policy allows.",
                riskTier = "medium",
                executionMode = "direct",
                input = "optional: {durationMs?: 1..1000, patternMs?: number[] up to 8 entries}",
                example = "{\"command\":\"hardware.vibrate\",\"input\":{\"durationMs\":100}}",
            ),
            DeviceActionCommandDescriptor(
                command = "hardware.audio_status",
                summary = "Return current music volume, max music volume, ringer mode, and fixed-volume policy status.",
                riskTier = "low",
                executionMode = "read_only",
                input = "none",
                example = "{\"command\":\"hardware.audio_status\"}",
            ),
            DeviceActionCommandDescriptor(
                command = "contacts.read",
                summary = "Query ContactsContract for contacts (display name, phone, email) with optional query filter, bounded to ~50 rows. Requires READ_CONTACTS permission.",
                riskTier = "medium",
                executionMode = "read_only",
                input = "optional: {query?: string, limit?: number (max 50)}",
                example = "{\"command\":\"contacts.read\",\"input\":{\"query\":\"John\",\"limit\":10}}",
            ),
            DeviceActionCommandDescriptor(
                command = "calendar.read",
                summary = "Query CalendarContract.Events for calendar events (title, dtstart, dtend, location) within a time window, bounded to ~50 rows. Requires READ_CALENDAR permission.",
                riskTier = "medium",
                executionMode = "read_only",
                input = "optional: {startTimeMillis?: number, endTimeMillis?: number, limit?: number (max 50)}",
                example = "{\"command\":\"calendar.read\",\"input\":{\"limit\":20}}",
            ),
        ),
    )
}

@Serializable
data class DeviceActionCatalog(
    val version: Int,
    val commands: List<DeviceActionCommandDescriptor>,
)

@Serializable
data class DeviceActionCommandDescriptor(
    val command: String,
    val summary: String,
    val riskTier: String,
    val executionMode: String,
    val input: String,
    val example: String,
)
