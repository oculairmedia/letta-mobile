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
        currentWorkingDirectory = firstString("current_working_directory", "currentWorkingDirectory"),
        currentPermissionMode = firstString("current_permission_mode", "currentPermissionMode")
            ?.let(AppServerPermissionMode::fromWireValue),
        cwdRevision = firstLong("cwd_revision", "cwdRevision"),
        gitContext = (this["git_context"] as? JsonObject ?: this["gitContext"] as? JsonObject)?.toGitContext(),
        lettaCodeVersion = firstString("letta_code_version", "lettaCodeVersion"),
        supportedCommands = stringListOrEmpty("supported_commands", "supportedCommands"),
        currentToolset = firstString("current_toolset", "currentToolset"),
        toolsetPreference = firstString("current_toolset_preference", "toolset_preference", "toolsetPreference"),
        availableToolsets = firstArray("available_toolsets", "availableToolsets")
            ?.mapNotNull { (it as? JsonObject)?.toToolset() } ?: emptyList(),
        backgroundProcesses = firstArray("background_processes", "backgroundProcesses")
            ?.mapNotNull { (it as? JsonObject)?.toBackgroundProcess() } ?: emptyList(),
        pendingControlRequests = firstArray("pending_control_requests", "pendingControlRequests")
            ?.mapNotNull { (it as? JsonObject)?.toPendingControlRequest() } ?: emptyList(),
        experiments = firstArray("experiments")
            ?.mapNotNull { (it as? JsonObject)?.toExperiment() } ?: emptyList(),
        memoryDirectory = firstString("memory_directory", "memoryDirectory"),
        isProcessing = firstBoolean("is_processing", "isProcessing") ?: false,
        isOnline = firstBoolean("is_online", "isOnline"),
    )

private fun JsonObject.toGitContext(): AppServerGitContext =
    AppServerGitContext(
        branch = firstString("branch", "current_branch", "currentBranch"),
        recentBranches = stringListOrEmpty("recent_branches", "recentBranches"),
    )

private fun JsonObject.toToolset(): AppServerToolset? {
    val id = firstString("id") ?: return null
    return AppServerToolset(
        id = id,
        displayName = firstString("display_name", "displayName"),
        label = firstString("label"),
        description = firstString("description"),
        isFeatured = firstBoolean("is_featured", "isFeatured") ?: false,
    )
}

private fun JsonObject.toBackgroundProcess(): AppServerBackgroundProcess? {
    val id = firstString("id", "process_id", "processId") ?: return null
    return AppServerBackgroundProcess(
        id = id,
        type = firstString("type", "kind"),
        description = firstString("description"),
        startedAt = firstString("started_at", "startedAt"),
        ageSeconds = firstLong("age_seconds", "ageSeconds", "age"),
    )
}

private fun JsonObject.toPendingControlRequest(): AppServerPendingControlRequest? {
    val requestId = firstString("request_id", "requestId") ?: return null
    return AppServerPendingControlRequest(
        requestId = requestId,
        request = this["request"] as? JsonObject ?: JsonObject(emptyMap()),
        agentId = firstString("agent_id", "agentId"),
        conversationId = firstString("conversation_id", "conversationId"),
    )
}

private fun JsonObject.toExperiment(): AppServerExperiment? {
    val id = firstString("id") ?: return null
    return AppServerExperiment(
        id = id,
        label = firstString("label"),
        description = firstString("description"),
        envVar = firstString("envVar", "env_var"),
        enabled = firstBoolean("enabled") ?: false,
        source = firstString("source"),
    )
}

private fun JsonObject.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }

private fun JsonObject.firstLong(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.longOrNull }

private fun JsonObject.firstBoolean(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.booleanOrNull }

private fun JsonObject.firstArray(vararg keys: String): JsonArray? =
    keys.firstNotNullOfOrNull { key -> this[key] as? JsonArray }

private fun JsonObject.stringListOrEmpty(vararg keys: String): List<String> =
    firstArray(*keys)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull } ?: emptyList()
