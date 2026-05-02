package com.letta.mobile.platform.systemaccess

import com.letta.mobile.platform.SystemAccessFlavor

/** Stable identifiers for Android/system capability gates. */
object SystemAccessCapabilityIds {
    const val APP_PRIVATE_STORAGE = "storage.app_private"
    const val SAF_STORAGE = "storage.saf"
    const val MEDIA_LIBRARY = "storage.media_library"
    const val ALL_FILES_STORAGE = "storage.all_files"
    const val CONTACTS_READ = "contacts.read"
    const val CONTACTS_WRITE = "contacts.write"
    const val OVERLAY = "overlay.floating_assistant"
    const val NOTIFICATION_LISTENER = "notifications.listener"
    const val POST_NOTIFICATIONS = "notifications.post"
    const val ACCESSIBILITY_SERVICE = "accessibility.service"
    const val LOCAL_SHELL = "shell.local"
    const val SHIZUKU_BRIDGE = "bridge.shizuku"
    const val SUI_BRIDGE = "bridge.sui"
    const val ROOT_SHELL = "shell.root"
    const val ROOT_FILESYSTEM = "filesystem.root"
    const val ROOT_PROFILE_GUIDANCE = "root.profile_guidance"
}

enum class SystemAccessFlavorAvailability {
    Supported,
    Unsupported,
    PolicyGated,
    DocumentationOnly,
}

data class SystemAccessFlavorSupport(
    val play: SystemAccessFlavorAvailability,
    val sideload: SystemAccessFlavorAvailability,
    val root: SystemAccessFlavorAvailability,
) {
    fun availabilityFor(flavor: SystemAccessFlavor): SystemAccessFlavorAvailability = when (flavor) {
        SystemAccessFlavor.Play -> play
        SystemAccessFlavor.Sideload -> sideload
        SystemAccessFlavor.Root -> root
    }
}

enum class SystemAccessCapabilityStatus {
    Unavailable,
    AvailableNeedsSetup,
    Denied,
    Granted,
    GrantedLimited,
    Revoked,
    Error,
}

enum class SystemAccessDataSensitivity {
    Public,
    AppPrivate,
    Personal,
    CrossApp,
    System,
    Root,
}

enum class SystemAccessApprovalPolicy {
    None,
    AskEveryTime,
    RememberPerSession,
    RememberPerScope,
    Forbidden,
}

enum class SystemAccessPermissionIntentKind {
    RuntimePermission,
    SettingsDeepLink,
    SystemPicker,
    Documentation,
}

data class SystemAccessPermissionIntent(
    val id: String,
    val label: String,
    val kind: SystemAccessPermissionIntentKind,
    val permissions: List<String> = emptyList(),
    val settingsAction: String? = null,
    val minSdk: Int? = null,
)

data class SystemAccessAuditPolicy(
    val loggedFields: List<String>,
    val redactedFields: List<String> = emptyList(),
    val localOnlyByDefault: Boolean = true,
)

data class SystemAccessPolicyRisk(
    val level: SystemAccessPolicyRiskLevel,
    val rationale: String,
)

enum class SystemAccessPolicyRiskLevel {
    Low,
    Medium,
    High,
    VeryHigh,
    NotPlayCompatible,
}

data class SystemAccessCapabilityDefinition(
    val id: String,
    val title: String,
    val summary: String,
    val flavorAvailability: SystemAccessFlavorSupport,
    val permissionIntents: List<SystemAccessPermissionIntent>,
    val dataSensitivity: SystemAccessDataSensitivity,
    val toolIds: Set<String>,
    val approvalPolicy: SystemAccessApprovalPolicy,
    val auditPolicy: SystemAccessAuditPolicy,
    val policyRisk: SystemAccessPolicyRisk,
    val probe: SystemAccessProbe,
    val defaultUserEnabled: Boolean = true,
)

data class SystemAccessCapability(
    val definition: SystemAccessCapabilityDefinition,
    val status: SystemAccessCapabilityStatus,
    val statusReason: String,
    val userEnabled: Boolean,
) {
    val id: String = definition.id
    val title: String = definition.title
    val summary: String = definition.summary
    val flavorAvailability: SystemAccessFlavorSupport = definition.flavorAvailability
    val permissionIntents: List<SystemAccessPermissionIntent> = definition.permissionIntents
    val dataSensitivity: SystemAccessDataSensitivity = definition.dataSensitivity
    val toolIds: Set<String> = definition.toolIds
    val approvalPolicy: SystemAccessApprovalPolicy = definition.approvalPolicy
    val auditPolicy: SystemAccessAuditPolicy = definition.auditPolicy
    val policyRisk: SystemAccessPolicyRisk = definition.policyRisk

    val isUsableForTools: Boolean = userEnabled &&
        approvalPolicy != SystemAccessApprovalPolicy.Forbidden &&
        status in setOf(SystemAccessCapabilityStatus.Granted, SystemAccessCapabilityStatus.GrantedLimited)
}

data class SystemAccessToolCheck(
    val toolId: String,
    val allowed: Boolean,
    val capabilityId: String? = null,
    val status: SystemAccessCapabilityStatus? = null,
    val reason: String,
)
