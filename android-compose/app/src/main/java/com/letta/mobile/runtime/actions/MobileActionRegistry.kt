package com.letta.mobile.runtime.actions

import android.content.Context
import android.content.pm.PackageManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

interface MobileActionCapabilityProvider {
    fun capabilities(): List<MobileActionCapability>
}

interface MobileExternalToolHandler : MobileActionCapabilityProvider {
    val toolName: String
    fun handle(input: JsonObject, actionId: String = newActionId()): MobileActionToolResponse
}

interface MobileActionAuditSink {
    fun record(event: MobileActionAuditEvent)
    fun recent(): List<MobileActionAuditEvent>
}

class InMemoryMobileActionAuditSink : MobileActionAuditSink {
    private val events = mutableListOf<MobileActionAuditEvent>()

    override fun record(event: MobileActionAuditEvent) {
        synchronized(events) { events += event }
    }

    override fun recent(): List<MobileActionAuditEvent> = synchronized(events) { events.toList() }
}

@Singleton
class MobileActionRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards MobileActionCapabilityProvider>,
    handlers: Set<@JvmSuppressWildcards MobileExternalToolHandler>,
    private val auditSink: MobileActionAuditSink,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val capabilityProviders = providers + handlers
    private val handlersByName = handlers.associateBy { it.toolName }

    fun matrix(): MobileActionCapabilityMatrix = MobileActionCapabilityMatrix(
        capabilities = capabilityProviders.flatMap { it.capabilities() }.sortedBy { it.id },
    )

    fun matrixJson(): String = json.encodeToString(matrix())

    fun handle(toolName: String, input: JsonObject, actionId: String = newActionId()): MobileActionToolResponse {
        val handler = handlersByName[toolName]
        val response = if (handler == null) {
            MobileActionToolResponse(
                success = false,
                toolName = toolName,
                status = MobileActionCapabilityStatus.Error,
                message = "Mobile external tool is not registered: $toolName",
                capabilityId = "unknown",
                actionId = actionId,
                error = "not_registered",
            )
        } else {
            handler.handle(input, actionId)
        }
        record(response)
        return response
    }

    private fun record(response: MobileActionToolResponse) {
        val capability = matrix().capabilities.firstOrNull { it.id == response.capabilityId }
        auditSink.record(
            MobileActionAuditEvent(
                timestampMillis = System.currentTimeMillis(),
                toolName = response.toolName,
                capabilityId = response.capabilityId,
                actionId = response.actionId,
                status = response.status,
                success = response.success,
                message = response.message,
                executionMode = capability?.executionMode ?: MobileActionExecutionMode.UserMediated,
                riskTier = capability?.riskTier ?: MobileActionRiskTier.Low,
            )
        )
    }
}

class AndroidMobileActionCapabilityProvider @Inject constructor(
    private val context: Context,
) : MobileActionCapabilityProvider {
    override fun capabilities(): List<MobileActionCapability> = listOf(
        MobileActionCapability(
            id = CapabilityIds.OPEN_APP_SETTINGS,
            toolName = "android.open_app_settings",
            label = "Open app settings",
            description = "Prepare an Android Settings intent for this app without changing settings directly.",
            status = MobileActionCapabilityStatus.SettingsRequired,
            riskTier = MobileActionRiskTier.Low,
            sensitivity = MobileActionSensitivity.DeviceState,
            executionMode = MobileActionExecutionMode.UserMediated,
            requiredSettings = listOf("android.settings.APPLICATION_DETAILS_SETTINGS"),
            reason = "Foundation only: future tools may ask the user to open app settings, but this PR does not launch actions.",
        ),
        MobileActionCapability(
            id = CapabilityIds.ACCESSIBILITY_SERVICE,
            toolName = "android.accessibility_action",
            label = "Accessibility mediated action",
            description = "Placeholder capability for future user-approved accessibility actions.",
            status = MobileActionCapabilityStatus.AccessibilityRequired,
            riskTier = MobileActionRiskTier.High,
            sensitivity = MobileActionSensitivity.CrossAppData,
            executionMode = MobileActionExecutionMode.Privileged,
            requiredSettings = listOf("android.settings.ACCESSIBILITY_SETTINGS"),
            reason = "Accessibility access must be granted by the user before any future handler can execute.",
        ),
        MobileActionCapability(
            id = CapabilityIds.SHIZUKU_BRIDGE,
            toolName = "android.shizuku_action",
            label = "Shizuku mediated action",
            description = "Placeholder capability for future Shizuku-backed privileged actions.",
            status = MobileActionCapabilityStatus.ShizukuRequired,
            riskTier = MobileActionRiskTier.High,
            sensitivity = MobileActionSensitivity.DeviceControl,
            executionMode = MobileActionExecutionMode.Privileged,
            requiredSettings = listOf("shizuku.permission.API_V23"),
            reason = "Shizuku is not integrated in this slice; future handlers must verify it before use.",
        ),
        MobileActionCapability(
            id = CapabilityIds.ROOT_BRIDGE,
            toolName = "android.root_action",
            label = "Root mediated action",
            description = "Placeholder capability for future root-backed actions.",
            status = MobileActionCapabilityStatus.RootRequired,
            riskTier = MobileActionRiskTier.Critical,
            sensitivity = MobileActionSensitivity.DeviceControl,
            executionMode = MobileActionExecutionMode.Privileged,
            reason = "Root execution is unavailable by default and no root action is implemented in this PR.",
        ),
        MobileActionCapability(
            id = CapabilityIds.CAMERA_HARDWARE,
            toolName = "android.camera_action",
            label = "Camera hardware action",
            description = "Placeholder capability for future camera hardware tools.",
            status = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                MobileActionCapabilityStatus.Disabled
            } else {
                MobileActionCapabilityStatus.UnsupportedHardware
            },
            riskTier = MobileActionRiskTier.Medium,
            sensitivity = MobileActionSensitivity.PersonalData,
            executionMode = MobileActionExecutionMode.UserMediated,
            requiredPermissions = listOf(android.Manifest.permission.CAMERA),
            requiredHardware = listOf("camera"),
            androidFeatures = listOf(PackageManager.FEATURE_CAMERA_ANY),
            reason = "Camera actions are intentionally disabled until a future PR adds opt-in and concrete handlers.",
        ),
    )

    object CapabilityIds {
        const val OPEN_APP_SETTINGS = "android.settings.open_app"
        const val ACCESSIBILITY_SERVICE = "android.accessibility.service"
        const val SHIZUKU_BRIDGE = "android.privileged.shizuku"
        const val ROOT_BRIDGE = "android.privileged.root"
        const val CAMERA_HARDWARE = "android.hardware.camera"
    }
}

fun newActionId(): String = "mobile-action-" + java.util.UUID.randomUUID().toString()
