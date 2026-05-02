@file:Suppress("MaxLineLength", "LongParameterList")

package com.letta.mobile.platform.systemaccess

import android.Manifest
import android.os.Build
import com.letta.mobile.platform.SystemAccessFlavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAccessCapabilityRegistryTest {
    @Test
    fun `play flavor fails closed for privileged tools`() {
        val registry = DefaultSystemAccessCapabilityRegistry(FakeSystemAccessEnvironment(flavor = SystemAccessFlavor.Play))

        val localShell = registry.getCapability(SystemAccessCapabilityIds.LOCAL_SHELL)
        val rootShell = registry.getCapability(SystemAccessCapabilityIds.ROOT_SHELL)

        assertEquals(SystemAccessCapabilityStatus.Unavailable, localShell?.status)
        assertEquals(SystemAccessCapabilityStatus.Unavailable, rootShell?.status)
        assertFalse(registry.canExposeTool("shell.local.run"))
        assertFalse(registry.canExposeTool("shell.root.run"))
    }

    @Test
    fun `sideload shell is discoverable but not exposed until granted and enabled`() {
        val registry = DefaultSystemAccessCapabilityRegistry(
            FakeSystemAccessEnvironment(
                flavor = SystemAccessFlavor.Sideload,
                localShellBuildEnabled = true,
                shizukuBuildEnabled = true,
            ),
        )

        val localShell = registry.getCapability(SystemAccessCapabilityIds.LOCAL_SHELL)
        val check = registry.checkToolAccess("shell.local.run")

        assertEquals(SystemAccessCapabilityStatus.AvailableNeedsSetup, localShell?.status)
        assertFalse(check.allowed)
        assertEquals(SystemAccessCapabilityIds.LOCAL_SHELL, check.capabilityId)
    }

    @Test
    fun `app-private storage is granted and exposes app-private tools`() {
        val registry = DefaultSystemAccessCapabilityRegistry(FakeSystemAccessEnvironment())

        val capability = registry.getCapability(SystemAccessCapabilityIds.APP_PRIVATE_STORAGE)
        val check = registry.checkToolAccess("storage.app_private.read")

        assertEquals(SystemAccessCapabilityStatus.Granted, capability?.status)
        assertTrue(check.allowed)
    }

    @Test
    fun `saf storage is limited and usable after persisted uri grant`() {
        val registry = DefaultSystemAccessCapabilityRegistry(
            FakeSystemAccessEnvironment(hasPersistedUriGrant = true),
        )

        val capability = registry.getCapability(SystemAccessCapabilityIds.SAF_STORAGE)
        val check = registry.checkToolAccess("storage.saf.read")

        assertEquals(SystemAccessCapabilityStatus.GrantedLimited, capability?.status)
        assertTrue(check.allowed)
    }

    @Test
    fun `contacts read is unavailable when manifest permission is absent`() {
        val registry = DefaultSystemAccessCapabilityRegistry(FakeSystemAccessEnvironment())

        val capability = registry.getCapability(SystemAccessCapabilityIds.CONTACTS_READ)
        val check = registry.checkToolAccess("contacts.lookup")

        assertEquals(SystemAccessCapabilityStatus.Unavailable, capability?.status)
        assertFalse(check.allowed)
    }

    @Test
    fun `contacts read is granted when declared and granted`() {
        val registry = DefaultSystemAccessCapabilityRegistry(
            FakeSystemAccessEnvironment(
                declaredPermissions = setOf(Manifest.permission.READ_CONTACTS),
                grantedPermissions = setOf(Manifest.permission.READ_CONTACTS),
            ),
        )

        val capability = registry.getCapability(SystemAccessCapabilityIds.CONTACTS_READ)

        assertEquals(SystemAccessCapabilityStatus.Granted, capability?.status)
        assertTrue(registry.canExposeTool("contacts.lookup"))
    }

    @Test
    fun `unknown tool ids fail closed`() {
        val registry = DefaultSystemAccessCapabilityRegistry(FakeSystemAccessEnvironment())

        val check = registry.checkToolAccess("unknown.tool")

        assertFalse(check.allowed)
        assertEquals(null, check.capabilityId)
        assertNotNull(check.reason)
    }

    @Test
    fun `post notifications is granted before runtime permission sdk`() {
        val registry = DefaultSystemAccessCapabilityRegistry(
            FakeSystemAccessEnvironment(sdkInt = Build.VERSION_CODES.S),
        )

        val capability = registry.getCapability(SystemAccessCapabilityIds.POST_NOTIFICATIONS)

        assertEquals(SystemAccessCapabilityStatus.Granted, capability?.status)
        assertTrue(registry.canExposeTool("notifications.post_status"))
    }

    private class FakeSystemAccessEnvironment(
        override val flavor: SystemAccessFlavor = SystemAccessFlavor.Play,
        override val sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        override val localShellBuildEnabled: Boolean = false,
        override val shizukuBuildEnabled: Boolean = false,
        override val rootToolsBuildEnabled: Boolean = false,
        private val declaredPermissions: Set<String> = emptySet(),
        private val grantedPermissions: Set<String> = emptySet(),
        private val declaredServices: Set<String> = emptySet(),
        private val systemFeatures: Set<String> = emptySet(),
        private val hasPersistedUriGrant: Boolean = false,
        private val canDrawOverlays: Boolean = false,
        private val enabledNotificationListeners: Set<String> = emptySet(),
        private val enabledAccessibilityServices: Set<String> = emptySet(),
    ) : SystemAccessEnvironment {
        override fun hasDeclaredPermission(permission: String): Boolean = permission in declaredPermissions
        override fun isPermissionGranted(permission: String): Boolean = permission in grantedPermissions
        override fun hasDeclaredService(serviceClassName: String): Boolean = serviceClassName in declaredServices
        override fun hasSystemFeature(featureName: String): Boolean = featureName in systemFeatures
        override fun hasPersistedUriGrant(): Boolean = hasPersistedUriGrant
        override fun canDrawOverlays(): Boolean = canDrawOverlays
        override fun isNotificationListenerEnabled(serviceClassName: String): Boolean = serviceClassName in enabledNotificationListeners
        override fun isAccessibilityServiceEnabled(serviceClassName: String): Boolean = serviceClassName in enabledAccessibilityServices
    }
}
