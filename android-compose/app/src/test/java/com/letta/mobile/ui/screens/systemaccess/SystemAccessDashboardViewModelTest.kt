package com.letta.mobile.ui.screens.systemaccess

import com.letta.mobile.platform.SystemAccessFlavor
import com.letta.mobile.platform.systemaccess.SystemAccessCapability
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityDefinitions
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityIds
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityRegistry
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityStatus
import com.letta.mobile.platform.systemaccess.SystemAccessEnvironment
import com.letta.mobile.platform.systemaccess.SystemAccessToolCheck
import com.letta.mobile.ui.common.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAccessDashboardViewModelTest {
    @Test
    fun `play flavor hides unsupported sideload and root capabilities`() {
        val viewModel = SystemAccessDashboardViewModel(
            registry = FakeRegistry(),
            environment = FakeEnvironment(flavor = SystemAccessFlavor.Play),
        )

        val state = viewModel.successState()
        val ids = state.groups.flatMap { group -> group.capabilities.map { it.id } }

        assertTrue(SystemAccessCapabilityIds.APP_PRIVATE_STORAGE in ids)
        assertTrue(SystemAccessCapabilityIds.CONTACTS_READ in ids)
        assertFalse(SystemAccessCapabilityIds.LOCAL_SHELL in ids)
        assertFalse(SystemAccessCapabilityIds.ROOT_SHELL in ids)
    }

    @Test
    fun `root flavor includes root capability group`() {
        val viewModel = SystemAccessDashboardViewModel(
            registry = FakeRegistry(),
            environment = FakeEnvironment(flavor = SystemAccessFlavor.Root),
        )

        val state = viewModel.successState()
        val rootGroup = state.groups.single { it.family == SystemAccessCapabilityFamily.Root }

        assertTrue(rootGroup.capabilities.any { it.id == SystemAccessCapabilityIds.ROOT_SHELL })
        assertEquals(SystemAccessFlavor.Root, state.flavor)
    }

    private fun SystemAccessDashboardViewModel.successState(): SystemAccessDashboardUiState =
        (uiState.value as UiState.Success).data

    private class FakeRegistry : SystemAccessCapabilityRegistry {
        override fun listCapabilities(): List<SystemAccessCapability> =
            SystemAccessCapabilityDefinitions.all.map { definition ->
                SystemAccessCapability(
                    definition = definition,
                    status = if (definition.id == SystemAccessCapabilityIds.APP_PRIVATE_STORAGE) {
                        SystemAccessCapabilityStatus.Granted
                    } else {
                        SystemAccessCapabilityStatus.AvailableNeedsSetup
                    },
                    statusReason = "fake",
                    userEnabled = definition.defaultUserEnabled,
                )
            }

        override fun getCapability(id: String): SystemAccessCapability? =
            listCapabilities().firstOrNull { it.id == id }

        override fun checkToolAccess(toolId: String): SystemAccessToolCheck =
            SystemAccessToolCheck(toolId = toolId, allowed = false, reason = "fake")
    }

    private class FakeEnvironment(
        override val flavor: SystemAccessFlavor,
    ) : SystemAccessEnvironment {
        override val sdkInt: Int = 35
        override val localShellBuildEnabled: Boolean = true
        override val shizukuBuildEnabled: Boolean = true
        override val rootToolsBuildEnabled: Boolean = true

        override fun hasDeclaredPermission(permission: String): Boolean = true
        override fun isPermissionGranted(permission: String): Boolean = false
        override fun hasDeclaredService(serviceClassName: String): Boolean = false
        override fun hasSystemFeature(featureName: String): Boolean = false
        override fun hasPersistedUriGrant(): Boolean = false
        override fun canDrawOverlays(): Boolean = false
        override fun isNotificationListenerEnabled(serviceClassName: String): Boolean = false
        override fun isAccessibilityServiceEnabled(serviceClassName: String): Boolean = false
    }
}
