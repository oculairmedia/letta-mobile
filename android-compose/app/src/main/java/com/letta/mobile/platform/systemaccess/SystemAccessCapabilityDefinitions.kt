@file:Suppress("MaxLineLength", "TooManyFunctions")

package com.letta.mobile.platform.systemaccess

import android.os.Build
import com.letta.mobile.platform.SystemAccessFlavor

sealed interface SystemAccessProbe {
    fun status(environment: SystemAccessEnvironment): ProbeResult

    data object AlwaysGranted : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult =
            ProbeResult(SystemAccessCapabilityStatus.Granted, "Available inside the app sandbox.")
    }

    data object StorageAccessFramework : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult =
            if (environment.hasPersistedUriGrant()) {
                ProbeResult(SystemAccessCapabilityStatus.GrantedLimited, "At least one user-selected document or tree grant is persisted.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Choose a document or folder with Android's system picker.")
            }
    }

    data class RuntimePermission(
        val permission: String,
        val minSdk: Int? = null,
        val preMinSdkStatus: SystemAccessCapabilityStatus = SystemAccessCapabilityStatus.Granted,
        val preMinSdkReason: String = "This Android version does not require the runtime permission.",
    ) : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult {
            if (minSdk != null && environment.sdkInt < minSdk) {
                return ProbeResult(preMinSdkStatus, preMinSdkReason)
            }
            if (!environment.hasDeclaredPermission(permission)) {
                return ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Required manifest permission is not declared in this build.")
            }
            return if (environment.isPermissionGranted(permission)) {
                ProbeResult(SystemAccessCapabilityStatus.Granted, "Android permission is granted.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.Denied, "Android permission is not granted.")
            }
        }
    }

    data class AnyRuntimePermission(
        val permissions: List<String>,
        val legacyPermission: String? = null,
        val modernMinSdk: Int = Build.VERSION_CODES.TIRAMISU,
    ) : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult {
            val relevantPermissions = if (environment.sdkInt >= modernMinSdk) permissions else listOfNotNull(legacyPermission)
            if (relevantPermissions.isEmpty()) {
                return ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Use Android's scoped media picker or SAF for this version.")
            }
            val declared = relevantPermissions.filter(environment::hasDeclaredPermission)
            if (declared.isEmpty()) {
                return ProbeResult(SystemAccessCapabilityStatus.Unavailable, "No media read permission is declared in this build.")
            }
            return if (declared.any(environment::isPermissionGranted)) {
                ProbeResult(SystemAccessCapabilityStatus.GrantedLimited, "At least one media read permission is granted.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.Denied, "Media read permission is not granted.")
            }
        }
    }

    data object Overlay : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult {
            if (!environment.hasDeclaredPermission(AndroidPermissionNames.SystemAlertWindow)) {
                return ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Overlay special-access permission is not declared in this build.")
            }
            return if (environment.canDrawOverlays()) {
                ProbeResult(SystemAccessCapabilityStatus.Granted, "Android overlay special access is enabled.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Enable overlay access in Android settings.")
            }
        }
    }

    data class NotificationListener(val serviceClassName: String) : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult {
            if (!environment.hasDeclaredService(serviceClassName)) {
                return ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Notification listener service is not declared in this build.")
            }
            return if (environment.isNotificationListenerEnabled(serviceClassName)) {
                ProbeResult(SystemAccessCapabilityStatus.Granted, "Notification listener access is enabled.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Enable notification access in Android settings.")
            }
        }
    }

    data class AccessibilityService(val serviceClassName: String) : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult {
            if (!environment.hasDeclaredService(serviceClassName)) {
                return ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Accessibility service is not declared in this build.")
            }
            return if (environment.isAccessibilityServiceEnabled(serviceClassName)) {
                ProbeResult(SystemAccessCapabilityStatus.Granted, "Accessibility service is enabled.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Enable the accessibility service in Android settings.")
            }
        }
    }

    data object LocalShell : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult =
            if (environment.localShellBuildEnabled) {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Local shell is compiled into this flavor but still needs explicit user setup.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Local shell is disabled by this build flavor.")
            }
    }

    data object ShizukuBridge : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult {
            if (!environment.shizukuBuildEnabled) {
                return ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Shizuku/Sui bridge code is disabled by this build flavor.")
            }
            if (!environment.hasDeclaredPermission(AndroidPermissionNames.ShizukuApiV23)) {
                return ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Shizuku API permission is not declared in this build.")
            }
            return if (environment.isPermissionGranted(AndroidPermissionNames.ShizukuApiV23)) {
                ProbeResult(SystemAccessCapabilityStatus.Granted, "Shizuku API permission is granted.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Install/start Shizuku or Sui and grant Letta access.")
            }
        }
    }

    data object SuiBridge : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult = ShizukuBridge.status(environment)
    }

    data object RootShell : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult =
            if (environment.rootToolsBuildEnabled) {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Root bridge is compiled into this flavor but requires user opt-in and external su approval.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Root tools are disabled by this build flavor.")
            }
    }

    data object RootFilesystem : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult =
            if (environment.rootToolsBuildEnabled) {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Root filesystem tools require root shell setup and per-scope approval.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Root filesystem tools are disabled by this build flavor.")
            }
    }

    data object DocumentationOnly : SystemAccessProbe {
        override fun status(environment: SystemAccessEnvironment): ProbeResult =
            if (environment.flavor == SystemAccessFlavor.Play) {
                ProbeResult(SystemAccessCapabilityStatus.Unavailable, "Root-profile guidance is not part of the Play flavor.")
            } else {
                ProbeResult(SystemAccessCapabilityStatus.AvailableNeedsSetup, "Documentation-only setup guidance is available for this flavor.")
            }
    }
}

data class ProbeResult(
    val status: SystemAccessCapabilityStatus,
    val reason: String,
)

object SystemAccessCapabilityDefinitions {
    val all: List<SystemAccessCapabilityDefinition> = listOf(
        appPrivateStorage(),
        safStorage(),
        mediaLibrary(),
        allFilesStorage(),
        contactsRead(),
        contactsWrite(),
        overlay(),
        notificationListener(),
        postNotifications(),
        accessibilityService(),
        localShell(),
        shizukuBridge(),
        suiBridge(),
        rootShell(),
        rootFilesystem(),
        rootProfileGuidance(),
    )

    private fun allFlavors() = SystemAccessFlavorSupport(
        play = SystemAccessFlavorAvailability.Supported,
        sideload = SystemAccessFlavorAvailability.Supported,
        root = SystemAccessFlavorAvailability.Supported,
    )

    private fun rootOnly() = SystemAccessFlavorSupport(
        play = SystemAccessFlavorAvailability.Unsupported,
        sideload = SystemAccessFlavorAvailability.Unsupported,
        root = SystemAccessFlavorAvailability.Supported,
    )

    private fun sideloadAndRoot() = SystemAccessFlavorSupport(
        play = SystemAccessFlavorAvailability.Unsupported,
        sideload = SystemAccessFlavorAvailability.Supported,
        root = SystemAccessFlavorAvailability.Supported,
    )

    private fun appPrivateStorage() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.APP_PRIVATE_STORAGE,
        title = "App-private storage",
        summary = "Read and write Letta-created files inside the app sandbox.",
        flavorAvailability = allFlavors(),
        permissionIntents = emptyList(),
        dataSensitivity = SystemAccessDataSensitivity.AppPrivate,
        toolIds = setOf("storage.app_private.read", "storage.app_private.write", "storage.app_private.export", "storage.app_private.cache"),
        approvalPolicy = SystemAccessApprovalPolicy.None,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "path", "operation")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.Low, "App sandbox access is normal app behavior."),
        probe = SystemAccessProbe.AlwaysGranted,
    )

    private fun safStorage() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.SAF_STORAGE,
        title = "User-selected files and folders",
        summary = "Use Android's document picker for scoped access to files or folders selected by the user.",
        flavorAvailability = allFlavors(),
        permissionIntents = listOf(
            SystemAccessPermissionIntent(
                id = "storage.saf.open_document",
                label = "Choose file",
                kind = SystemAccessPermissionIntentKind.SystemPicker,
                settingsAction = AndroidSettingsActions.ActionOpenDocument,
            ),
            SystemAccessPermissionIntent(
                id = "storage.saf.open_tree",
                label = "Choose folder",
                kind = SystemAccessPermissionIntentKind.SystemPicker,
                settingsAction = AndroidSettingsActions.ActionOpenDocumentTree,
            ),
        ),
        dataSensitivity = SystemAccessDataSensitivity.Personal,
        toolIds = setOf("storage.saf.read", "storage.saf.write", "storage.saf.search"),
        approvalPolicy = SystemAccessApprovalPolicy.RememberPerScope,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "uri", "operation", "scope"), redactedFields = listOf("contentPreview")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.Low, "SAF is scoped and user initiated."),
        probe = SystemAccessProbe.StorageAccessFramework,
    )

    private fun mediaLibrary() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.MEDIA_LIBRARY,
        title = "Media library",
        summary = "Import or attach user media after Android media permission is granted.",
        flavorAvailability = allFlavors(),
        permissionIntents = listOf(
            SystemAccessPermissionIntent(
                id = "storage.media_library.read",
                label = "Allow selected media access",
                kind = SystemAccessPermissionIntentKind.RuntimePermission,
                permissions = listOf(AndroidPermissionNames.ReadMediaImages, AndroidPermissionNames.ReadMediaVideo, AndroidPermissionNames.ReadMediaAudio),
                minSdk = Build.VERSION_CODES.TIRAMISU,
            ),
        ),
        dataSensitivity = SystemAccessDataSensitivity.Personal,
        toolIds = setOf("media.search", "media.import", "media.attach"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "mediaType", "operation"), redactedFields = listOf("mediaContent")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.Medium, "Media can contain sensitive personal data and must be minimized."),
        probe = SystemAccessProbe.AnyRuntimePermission(
            permissions = listOf(AndroidPermissionNames.ReadMediaImages, AndroidPermissionNames.ReadMediaVideo, AndroidPermissionNames.ReadMediaAudio),
            legacyPermission = AndroidPermissionNames.ReadExternalStorage,
        ),
    )

    private fun allFilesStorage() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.ALL_FILES_STORAGE,
        title = "All-files storage",
        summary = "Whole-device file access through Android special access or root-backed tools.",
        flavorAvailability = SystemAccessFlavorSupport(
            play = SystemAccessFlavorAvailability.Unsupported,
            sideload = SystemAccessFlavorAvailability.PolicyGated,
            root = SystemAccessFlavorAvailability.Supported,
        ),
        permissionIntents = listOf(
            SystemAccessPermissionIntent(
                id = "storage.all_files.settings",
                label = "Open all-files access settings",
                kind = SystemAccessPermissionIntentKind.SettingsDeepLink,
                permissions = listOf(AndroidPermissionNames.ManageExternalStorage),
                settingsAction = AndroidSettingsActions.ActionManageAppAllFilesAccessPermission,
                minSdk = Build.VERSION_CODES.R,
            ),
        ),
        dataSensitivity = SystemAccessDataSensitivity.System,
        toolIds = setOf("storage.all_files.search", "storage.all_files.read", "storage.all_files.write"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "path", "operation", "approvalId"), redactedFields = listOf("fileContent")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.High, "Broad storage access is generally unsuitable for Play without a core file-manager use case."),
        probe = SystemAccessProbe.RuntimePermission(AndroidPermissionNames.ManageExternalStorage, minSdk = Build.VERSION_CODES.R),
        defaultUserEnabled = false,
    )

    private fun contactsRead() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.CONTACTS_READ,
        title = "Contacts read",
        summary = "Look up contacts locally and share only user-approved snippets with agents.",
        flavorAvailability = allFlavors(),
        permissionIntents = listOf(runtimeIntent("contacts.read.permission", "Allow contacts lookup", AndroidPermissionNames.ReadContacts)),
        dataSensitivity = SystemAccessDataSensitivity.Personal,
        toolIds = setOf("contacts.lookup", "contacts.prompt_snippet"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "contactId", "fieldsShared"), redactedFields = listOf("phone", "email")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.Medium, "Contacts are sensitive personal data and require disclosure/minimization."),
        probe = SystemAccessProbe.RuntimePermission(AndroidPermissionNames.ReadContacts),
    )

    private fun contactsWrite() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.CONTACTS_WRITE,
        title = "Contacts write",
        summary = "Create or update contacts only after previewing the diff.",
        flavorAvailability = SystemAccessFlavorSupport(
            play = SystemAccessFlavorAvailability.PolicyGated,
            sideload = SystemAccessFlavorAvailability.Supported,
            root = SystemAccessFlavorAvailability.Supported,
        ),
        permissionIntents = listOf(runtimeIntent("contacts.write.permission", "Allow contacts writes", AndroidPermissionNames.WriteContacts)),
        dataSensitivity = SystemAccessDataSensitivity.Personal,
        toolIds = setOf("contacts.create", "contacts.update"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "contactId", "diff", "approvalId"), redactedFields = listOf("phone", "email")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.High, "Writing contacts is higher risk and should be justified separately for Play."),
        probe = SystemAccessProbe.RuntimePermission(AndroidPermissionNames.WriteContacts),
        defaultUserEnabled = false,
    )

    private fun overlay() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.OVERLAY,
        title = "Floating assistant overlay",
        summary = "Show a persistent floating assistant surface above other apps after Android special access is enabled.",
        flavorAvailability = allFlavors(),
        permissionIntents = listOf(settingsIntent("overlay.settings", "Open overlay settings", AndroidSettingsActions.ActionManageOverlayPermission, AndroidPermissionNames.SystemAlertWindow)),
        dataSensitivity = SystemAccessDataSensitivity.CrossApp,
        toolIds = setOf("overlay.floating_assistant"),
        approvalPolicy = SystemAccessApprovalPolicy.RememberPerScope,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "surface", "action")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.Medium, "Overlays must not be deceptive or interfere with other apps."),
        probe = SystemAccessProbe.Overlay,
    )

    private fun notificationListener() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.NOTIFICATION_LISTENER,
        title = "Notification listener",
        summary = "Read notifications for local triage and summaries after Android notification access is enabled.",
        flavorAvailability = allFlavors(),
        permissionIntents = listOf(settingsIntent("notifications.listener.settings", "Open notification access settings", AndroidSettingsActions.ActionNotificationListenerSettings)),
        dataSensitivity = SystemAccessDataSensitivity.CrossApp,
        toolIds = setOf("notifications.triage", "notifications.summarize", "notifications.action_suggest"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "packageName", "notificationFields", "forwardingDecision"), redactedFields = listOf("title", "text")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.High, "Notifications may contain cross-app private content."),
        probe = SystemAccessProbe.NotificationListener("com.letta.mobile.platform.LettaNotificationListenerService"),
        defaultUserEnabled = false,
    )

    private fun postNotifications() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.POST_NOTIFICATIONS,
        title = "Post notifications",
        summary = "Show Letta status alerts, long-running task completion, and approval prompts.",
        flavorAvailability = allFlavors(),
        permissionIntents = listOf(
            SystemAccessPermissionIntent(
                id = "notifications.post.permission",
                label = "Allow Letta notifications",
                kind = SystemAccessPermissionIntentKind.RuntimePermission,
                permissions = listOf(AndroidPermissionNames.PostNotifications),
                minSdk = Build.VERSION_CODES.TIRAMISU,
            ),
        ),
        dataSensitivity = SystemAccessDataSensitivity.AppPrivate,
        toolIds = setOf("notifications.post_status", "notifications.approval_prompt"),
        approvalPolicy = SystemAccessApprovalPolicy.None,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "channelId", "notificationType")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.Medium, "Notification volume and content must remain user-appropriate."),
        probe = SystemAccessProbe.RuntimePermission(
            permission = AndroidPermissionNames.PostNotifications,
            minSdk = Build.VERSION_CODES.TIRAMISU,
        ),
    )

    private fun accessibilityService() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.ACCESSIBILITY_SERVICE,
        title = "Accessibility service",
        summary = "Inspect UI context or perform user-driven UI automation only after a high-friction education flow.",
        flavorAvailability = SystemAccessFlavorSupport(
            play = SystemAccessFlavorAvailability.PolicyGated,
            sideload = SystemAccessFlavorAvailability.Supported,
            root = SystemAccessFlavorAvailability.Supported,
        ),
        permissionIntents = listOf(settingsIntent("accessibility.settings", "Open accessibility settings", AndroidSettingsActions.ActionAccessibilitySettings, AndroidPermissionNames.BindAccessibilityService)),
        dataSensitivity = SystemAccessDataSensitivity.CrossApp,
        toolIds = setOf("accessibility.inspect", "accessibility.ui_automation", "accessibility.screen_summary"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "packageName", "eventType", "automationAction"), redactedFields = listOf("visibleText")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.VeryHigh, "Play accessibility policy is strict and requires a truthful accessibility use case."),
        probe = SystemAccessProbe.AccessibilityService("com.letta.mobile.platform.LettaAccessibilityService"),
        defaultUserEnabled = false,
    )

    private fun localShell() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.LOCAL_SHELL,
        title = "No-root local shell",
        summary = "Run commands as Letta's app UID inside scoped working directories.",
        flavorAvailability = sideloadAndRoot(),
        permissionIntents = emptyList(),
        dataSensitivity = SystemAccessDataSensitivity.System,
        toolIds = setOf("shell.local.run"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "command", "cwd", "exitCode", "durationMs"), redactedFields = listOf("environment", "stdout", "stderr")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.High, "Local command execution has executable-code and abuse concerns for Play."),
        probe = SystemAccessProbe.LocalShell,
        defaultUserEnabled = false,
    )

    private fun shizukuBridge() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.SHIZUKU_BRIDGE,
        title = "Shizuku bridge",
        summary = "Use user-granted Shizuku APIs for delegated privileged Android operations.",
        flavorAvailability = sideloadAndRoot(),
        permissionIntents = listOf(runtimeIntent("bridge.shizuku.permission", "Grant Shizuku access", AndroidPermissionNames.ShizukuApiV23)),
        dataSensitivity = SystemAccessDataSensitivity.System,
        toolIds = setOf("shizuku.package_ops", "shizuku.appops", "shizuku.settings"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "api", "targetPackage", "mutation"), redactedFields = listOf("payload")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.High, "Privileged delegated APIs may appear to bypass Android's normal permission model."),
        probe = SystemAccessProbe.ShizukuBridge,
        defaultUserEnabled = false,
    )

    private fun suiBridge() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.SUI_BRIDGE,
        title = "Sui bridge",
        summary = "Use a root-backed Sui module exposing Shizuku-compatible APIs.",
        flavorAvailability = sideloadAndRoot(),
        permissionIntents = listOf(runtimeIntent("bridge.sui.permission", "Grant Sui/Shizuku access", AndroidPermissionNames.ShizukuApiV23)),
        dataSensitivity = SystemAccessDataSensitivity.Root,
        toolIds = setOf("sui.package_ops", "sui.appops", "sui.settings"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "api", "targetPackage", "mutation"), redactedFields = listOf("payload")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.High, "Sui is root-backed delegated privilege and is sideload/root only."),
        probe = SystemAccessProbe.SuiBridge,
        defaultUserEnabled = false,
    )

    private fun rootShell() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.ROOT_SHELL,
        title = "Root shell",
        summary = "Run commands through an external su provider after Letta opt-in and per-command approval.",
        flavorAvailability = rootOnly(),
        permissionIntents = emptyList(),
        dataSensitivity = SystemAccessDataSensitivity.Root,
        toolIds = setOf("shell.root.run", "root.environment_probe"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "command", "cwd", "exitCode", "approvalId", "providerHint"), redactedFields = listOf("environment", "stdout", "stderr")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.NotPlayCompatible, "Root command execution is not Play-compatible."),
        probe = SystemAccessProbe.RootShell,
        defaultUserEnabled = false,
    )

    private fun rootFilesystem() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.ROOT_FILESYSTEM,
        title = "Root filesystem tools",
        summary = "Read, search, or mutate root-only filesystem paths within approved scopes.",
        flavorAvailability = rootOnly(),
        permissionIntents = emptyList(),
        dataSensitivity = SystemAccessDataSensitivity.Root,
        toolIds = setOf("filesystem.root.read", "filesystem.root.search", "filesystem.root.write"),
        approvalPolicy = SystemAccessApprovalPolicy.AskEveryTime,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("toolId", "path", "operation", "approvalId", "dryRun"), redactedFields = listOf("fileContent")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.NotPlayCompatible, "Root filesystem access is not Play-compatible and requires path scoping."),
        probe = SystemAccessProbe.RootFilesystem,
        defaultUserEnabled = false,
    )

    private fun rootProfileGuidance() = SystemAccessCapabilityDefinition(
        id = SystemAccessCapabilityIds.ROOT_PROFILE_GUIDANCE,
        title = "KernelSU / SukiSU profile guidance",
        summary = "Documentation-only setup checklist for external root manager least-privilege profiles.",
        flavorAvailability = SystemAccessFlavorSupport(
            play = SystemAccessFlavorAvailability.Unsupported,
            sideload = SystemAccessFlavorAvailability.DocumentationOnly,
            root = SystemAccessFlavorAvailability.DocumentationOnly,
        ),
        permissionIntents = listOf(
            SystemAccessPermissionIntent(
                id = "root.profile_guidance.docs",
                label = "Open setup guidance",
                kind = SystemAccessPermissionIntentKind.Documentation,
            ),
        ),
        dataSensitivity = SystemAccessDataSensitivity.Public,
        toolIds = emptySet(),
        approvalPolicy = SystemAccessApprovalPolicy.None,
        auditPolicy = SystemAccessAuditPolicy(loggedFields = listOf("openedAt")),
        policyRisk = SystemAccessPolicyRisk(SystemAccessPolicyRiskLevel.Low, "Documentation is low risk outside Play listing copy."),
        probe = SystemAccessProbe.DocumentationOnly,
    )

    private fun runtimeIntent(id: String, label: String, permission: String) = SystemAccessPermissionIntent(
        id = id,
        label = label,
        kind = SystemAccessPermissionIntentKind.RuntimePermission,
        permissions = listOf(permission),
    )

    private fun settingsIntent(
        id: String,
        label: String,
        action: String,
        permission: String? = null,
    ) = SystemAccessPermissionIntent(
        id = id,
        label = label,
        kind = SystemAccessPermissionIntentKind.SettingsDeepLink,
        permissions = listOfNotNull(permission),
        settingsAction = action,
    )
}
