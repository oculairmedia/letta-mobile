package com.letta.mobile.runtime.actions

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileActionRegistryTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `capability matrix serializes exact stable shape`() {
        val registry = MobileActionRegistry(
            providers = setOf(SampleCapabilityProvider()),
            handlers = emptySet(),
            auditSink = InMemoryMobileActionAuditSink(),
        )

        val obj = json.parseToJsonElement(registry.matrixJson()).jsonObject
        val capability = obj["capabilities"]!!.jsonArray.single().jsonObject

        assertEquals("1", obj["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals("android", obj["platform"]!!.jsonPrimitive.content)
        assertEquals("android.sample.capability", capability["id"]!!.jsonPrimitive.content)
        assertEquals("android.sample_action", capability["toolName"]!!.jsonPrimitive.content)
        assertEquals("available", capability["status"]!!.jsonPrimitive.content)
        assertEquals("medium", capability["riskTier"]!!.jsonPrimitive.content)
        assertEquals("device_state", capability["sensitivity"]!!.jsonPrimitive.content)
        assertEquals("user_mediated", capability["executionMode"]!!.jsonPrimitive.content)
        assertEquals("android.permission.CAMERA", capability["requiredPermissions"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", capability["requiredSettings"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("camera", capability["requiredHardware"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("android.hardware.camera.any", capability["androidFeatures"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(capability["defaultEnabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `tool response serializes exact stable envelope`() {
        val response = MobileActionToolResponse(
            success = false,
            toolName = "android.sample_action",
            status = MobileActionCapabilityStatus.SettingsRequired,
            message = "Open Android settings to continue.",
            capabilityId = "android.sample.capability",
            actionId = "action-1",
            requiresUserAction = true,
            intentAction = "android.settings.APPLICATION_DETAILS_SETTINGS",
            error = "settings_required",
        )

        val obj = json.parseToJsonElement(json.encodeToString(response)).jsonObject

        assertEquals("false", obj["success"]!!.jsonPrimitive.content)
        assertEquals("android.sample_action", obj["toolName"]!!.jsonPrimitive.content)
        assertEquals("settings_required", obj["status"]!!.jsonPrimitive.content)
        assertEquals("Open Android settings to continue.", obj["message"]!!.jsonPrimitive.content)
        assertEquals("android.sample.capability", obj["capabilityId"]!!.jsonPrimitive.content)
        assertEquals("action-1", obj["actionId"]!!.jsonPrimitive.content)
        assertEquals("true", obj["requiresUserAction"]!!.jsonPrimitive.content)
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", obj["intentAction"]!!.jsonPrimitive.content)
        assertEquals("settings_required", obj["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `all required capability statuses are encoded as snake case`() {
        val values = MobileActionCapabilityStatus.entries.map { status ->
            json.parseToJsonElement(json.encodeToString(status)).jsonPrimitive.content
        }

        assertTrue(values.containsAll(listOf(
            "available",
            "disabled",
            "permission_required",
            "not_granted",
            "settings_required",
            "accessibility_required",
            "shizuku_required",
            "root_required",
            "unsupported_hardware",
            "blocked_by_android_policy",
            "error",
        )))
    }

    private class SampleCapabilityProvider : MobileActionCapabilityProvider {
        override fun capabilities(): List<MobileActionCapability> = listOf(
            MobileActionCapability(
                id = "android.sample.capability",
                toolName = "android.sample_action",
                label = "Sample action",
                description = "Sample capability used to pin the JSON contract.",
                status = MobileActionCapabilityStatus.Available,
                riskTier = MobileActionRiskTier.Medium,
                sensitivity = MobileActionSensitivity.DeviceState,
                executionMode = MobileActionExecutionMode.UserMediated,
                requiredPermissions = listOf("android.permission.CAMERA"),
                requiredSettings = listOf("android.settings.APPLICATION_DETAILS_SETTINGS"),
                requiredHardware = listOf("camera"),
                androidFeatures = listOf("android.hardware.camera.any"),
                reason = "test",
            )
        )
    }
}
