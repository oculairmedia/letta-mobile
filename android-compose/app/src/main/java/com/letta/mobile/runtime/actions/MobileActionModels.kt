package com.letta.mobile.runtime.actions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class MobileActionCapabilityStatus {
    @SerialName("available") Available,
    @SerialName("disabled") Disabled,
    @SerialName("permission_required") PermissionRequired,
    @SerialName("not_granted") NotGranted,
    @SerialName("settings_required") SettingsRequired,
    @SerialName("accessibility_required") AccessibilityRequired,
    @SerialName("shizuku_required") ShizukuRequired,
    @SerialName("root_required") RootRequired,
    @SerialName("unsupported_hardware") UnsupportedHardware,
    @SerialName("blocked_by_android_policy") BlockedByAndroidPolicy,
    @SerialName("error") Error,
}

@Serializable
enum class MobileActionRiskTier {
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
    @SerialName("critical") Critical,
}

@Serializable
enum class MobileActionSensitivity {
    @SerialName("none") None,
    @SerialName("device_state") DeviceState,
    @SerialName("personal_data") PersonalData,
    @SerialName("cross_app_data") CrossAppData,
    @SerialName("location") Location,
    @SerialName("health") Health,
    @SerialName("device_control") DeviceControl,
}

@Serializable
enum class MobileActionExecutionMode {
    @SerialName("direct") Direct,
    @SerialName("user_mediated") UserMediated,
    @SerialName("privileged") Privileged,
}

@Serializable
data class MobileActionCapability(
    val id: String,
    val toolName: String,
    val label: String,
    val description: String,
    val status: MobileActionCapabilityStatus,
    val riskTier: MobileActionRiskTier,
    val sensitivity: MobileActionSensitivity,
    val executionMode: MobileActionExecutionMode,
    val defaultEnabled: Boolean = false,
    val requiredPermissions: List<String> = emptyList(),
    val requiredSettings: List<String> = emptyList(),
    val requiredHardware: List<String> = emptyList(),
    val androidFeatures: List<String> = emptyList(),
    val reason: String,
)

@Serializable
data class MobileActionCapabilityMatrix(
    val schemaVersion: Int = 1,
    val platform: String = "android",
    val capabilities: List<MobileActionCapability>,
)

@Serializable
data class MobileActionToolResponse(
    val success: Boolean,
    val toolName: String,
    val status: MobileActionCapabilityStatus,
    val message: String,
    val capabilityId: String,
    val actionId: String,
    val requiresUserAction: Boolean = false,
    val intentAction: String? = null,
    val data: JsonObject? = null,
    val error: String? = null,
)

@Serializable
data class MobileActionAuditEvent(
    val timestampMillis: Long,
    val toolName: String,
    val capabilityId: String,
    val actionId: String,
    val status: MobileActionCapabilityStatus,
    val success: Boolean,
    val message: String,
    val executionMode: MobileActionExecutionMode,
    val riskTier: MobileActionRiskTier,
)
