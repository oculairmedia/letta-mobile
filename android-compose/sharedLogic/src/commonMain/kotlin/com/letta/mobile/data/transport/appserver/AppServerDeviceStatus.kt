package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Git context associated with the device's current working directory (letta-mobile-bzvro.19). */
data class AppServerGitContext(
    val branch: String?,
    val recentBranches: List<String> = emptyList(),
)

/** An available toolset advertised by the App Server (letta-mobile-bzvro.19, .22). */
data class AppServerToolset(
    val id: String,
    val displayName: String? = null,
    val label: String? = null,
    val description: String? = null,
    val isFeatured: Boolean = false,
)

/** A background process tracked on the device (letta-mobile-bzvro.19, .21). */
data class AppServerBackgroundProcess(
    val id: String,
    val type: String? = null,
    val description: String? = null,
    val startedAt: String? = null,
    val ageSeconds: Long? = null,
)

/** An experiment flag reported by the App Server (letta-mobile-bzvro.19). */
data class AppServerExperiment(
    val id: String,
    val label: String? = null,
    val description: String? = null,
    val envVar: String? = null,
    val enabled: Boolean = false,
    val source: String? = null,
)

/** A pending control request reported in device status (letta-mobile-bzvro.19). */
data class AppServerPendingControlRequest(
    val requestId: String,
    val request: JsonObject = JsonObject(emptyMap()),
    val agentId: String? = null,
    val conversationId: String? = null,
)

/**
 * letta-mobile-bzvro.19: the typed subset of an `update_device_status` snapshot
 * (protocol_v2 `DeviceStatus`) that the client acts on. The frame keeps the full
 * [AppServerInboundFrame.UpdateDeviceStatus.deviceStatus] object, so unknown and
 * future fields stay tolerated; a missing or malformed field reads as null.
 */
data class AppServerDeviceStatusSnapshot(
    /** `current_working_directory` — the server's realpath of the scope's cwd. */
    val currentWorkingDirectory: String? = null,
    /** `current_permission_mode`; null when absent or not a mode this client knows. */
    val currentPermissionMode: AppServerPermissionMode? = null,
    /** `cwd_revision` — monotonic signal for cwd changes and rejected stale cwd requests. */
    val cwdRevision: Long? = null,
    /** Git branch and recent branches for the current working directory. */
    val gitContext: AppServerGitContext? = null,
    /** Server version string (e.g. "0.33.6"). */
    val lettaCodeVersion: String? = null,
    /** Slash commands supported by the device. */
    val supportedCommands: List<String> = emptyList(),
    /** Currently active toolset id. */
    val currentToolset: String? = null,
    /** Current toolset preference (e.g. "auto", "default", "codex"). */
    val toolsetPreference: String? = null,
    /** List of available toolsets advertised by the server. */
    val availableToolsets: List<AppServerToolset> = emptyList(),
    /** List of background processes currently running on the server. */
    val backgroundProcesses: List<AppServerBackgroundProcess> = emptyList(),
    /** Pending control requests queued on the server. */
    val pendingControlRequests: List<AppServerPendingControlRequest> = emptyList(),
    /** Feature experiments supported/configured on the server. */
    val experiments: List<AppServerExperiment> = emptyList(),
    /** Memory / MemFS root directory on disk. */
    val memoryDirectory: String? = null,
    /** Whether the server is currently processing turns. */
    val isProcessing: Boolean = false,
    /** Whether the device connection is online. */
    val isOnline: Boolean? = null,
)

typealias DeviceStatusSnapshot = AppServerDeviceStatusSnapshot

val AppServerInboundFrame.UpdateDeviceStatus.snapshot: AppServerDeviceStatusSnapshot
    get() = deviceStatus.toDeviceStatusSnapshot()

internal fun JsonObject.toDeviceStatusSnapshot(): AppServerDeviceStatusSnapshot =
    AppServerDeviceStatusSnapshot(
        currentWorkingDirectory = stringOrNull("current_working_directory") ?: stringOrNull("currentWorkingDirectory"),
        currentPermissionMode = (stringOrNull("current_permission_mode") ?: stringOrNull("currentPermissionMode"))
            ?.let(AppServerPermissionMode::fromWireValue),
        cwdRevision = (this["cwd_revision"] as? JsonPrimitive)?.longOrNull
            ?: (this["cwdRevision"] as? JsonPrimitive)?.longOrNull,
        gitContext = (this["git_context"] as? JsonObject ?: this["gitContext"] as? JsonObject)?.toGitContext(),
        lettaCodeVersion = stringOrNull("letta_code_version") ?: stringOrNull("lettaCodeVersion"),
        supportedCommands = stringListOrEmpty("supported_commands")
            .ifEmpty { stringListOrEmpty("supportedCommands") },
        currentToolset = stringOrNull("current_toolset") ?: stringOrNull("currentToolset"),
        toolsetPreference = stringOrNull("current_toolset_preference")
            ?: stringOrNull("toolset_preference")
            ?: stringOrNull("toolsetPreference"),
        availableToolsets = (this["available_toolsets"] as? JsonArray ?: this["availableToolsets"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.toToolset() } ?: emptyList(),
        backgroundProcesses = (this["background_processes"] as? JsonArray ?: this["backgroundProcesses"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.toBackgroundProcess() } ?: emptyList(),
        pendingControlRequests = (this["pending_control_requests"] as? JsonArray ?: this["pendingControlRequests"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.toPendingControlRequest() } ?: emptyList(),
        experiments = (this["experiments"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.toExperiment() } ?: emptyList(),
        memoryDirectory = stringOrNull("memory_directory") ?: stringOrNull("memoryDirectory"),
        isProcessing = booleanOrNull("is_processing") ?: booleanOrNull("isProcessing") ?: false,
        isOnline = booleanOrNull("is_online") ?: booleanOrNull("isOnline"),
    )

private fun JsonObject.toGitContext(): AppServerGitContext {
    val branch = stringOrNull("branch") ?: stringOrNull("current_branch") ?: stringOrNull("currentBranch")
    val recent = stringListOrEmpty("recent_branches").ifEmpty { stringListOrEmpty("recentBranches") }
    return AppServerGitContext(branch = branch, recentBranches = recent)
}

private fun JsonObject.toToolset(): AppServerToolset? {
    val id = stringOrNull("id") ?: return null
    return AppServerToolset(
        id = id,
        displayName = stringOrNull("display_name") ?: stringOrNull("displayName"),
        label = stringOrNull("label"),
        description = stringOrNull("description"),
        isFeatured = booleanOrNull("is_featured") ?: booleanOrNull("isFeatured") ?: false,
    )
}

private fun JsonObject.toBackgroundProcess(): AppServerBackgroundProcess? {
    val id = stringOrNull("id") ?: stringOrNull("process_id") ?: stringOrNull("processId") ?: return null
    return AppServerBackgroundProcess(
        id = id,
        type = stringOrNull("type") ?: stringOrNull("kind"),
        description = stringOrNull("description"),
        startedAt = stringOrNull("started_at") ?: stringOrNull("startedAt"),
        ageSeconds = (this["age_seconds"] as? JsonPrimitive)?.longOrNull
            ?: (this["ageSeconds"] as? JsonPrimitive)?.longOrNull
            ?: (this["age"] as? JsonPrimitive)?.longOrNull,
    )
}

private fun JsonObject.toPendingControlRequest(): AppServerPendingControlRequest? {
    val requestId = stringOrNull("request_id") ?: stringOrNull("requestId") ?: return null
    val req = this["request"] as? JsonObject ?: JsonObject(emptyMap())
    return AppServerPendingControlRequest(
        requestId = requestId,
        request = req,
        agentId = stringOrNull("agent_id") ?: stringOrNull("agentId"),
        conversationId = stringOrNull("conversation_id") ?: stringOrNull("conversationId"),
    )
}

private fun JsonObject.toExperiment(): AppServerExperiment? {
    val id = stringOrNull("id") ?: return null
    return AppServerExperiment(
        id = id,
        label = stringOrNull("label"),
        description = stringOrNull("description"),
        envVar = stringOrNull("envVar") ?: stringOrNull("env_var"),
        enabled = booleanOrNull("enabled") ?: false,
        source = stringOrNull("source"),
    )
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringListOrEmpty(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull } ?: emptyList()
