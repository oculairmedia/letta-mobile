package com.letta.mobile.ui.screens.systemaccess

import androidx.lifecycle.ViewModel
import com.letta.mobile.platform.SystemAccessFlavor
import com.letta.mobile.platform.systemaccess.SystemAccessCapability
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityIds
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityRegistry
import com.letta.mobile.platform.systemaccess.SystemAccessEnvironment
import com.letta.mobile.platform.systemaccess.SystemAccessFlavorAvailability
import com.letta.mobile.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@androidx.compose.runtime.Immutable
data class SystemAccessDashboardUiState(
    val flavor: SystemAccessFlavor,
    val groups: ImmutableList<SystemAccessCapabilityGroupUi>,
    val grantedCount: Int,
    val visibleCount: Int,
)

@androidx.compose.runtime.Immutable
data class SystemAccessCapabilityGroupUi(
    val family: SystemAccessCapabilityFamily,
    val capabilities: ImmutableList<SystemAccessCapability>,
)

enum class SystemAccessCapabilityFamily {
    Storage,
    PersonalData,
    CrossAppFramework,
    ShellDelegatedPrivilege,
    Root,
}

@HiltViewModel
class SystemAccessDashboardViewModel @Inject constructor(
    private val registry: SystemAccessCapabilityRegistry,
    private val environment: SystemAccessEnvironment,
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<SystemAccessDashboardUiState>>(UiState.Loading)
    val uiState: StateFlow<UiState<SystemAccessDashboardUiState>> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val capabilities = registry.listCapabilities()
            .filter { capability ->
                val availability = capability.flavorAvailability.availabilityFor(environment.flavor)
                availability != SystemAccessFlavorAvailability.Unsupported
            }

        val groups = SystemAccessCapabilityFamily.entries.mapNotNull { family ->
            val groupCapabilities = capabilities
                .filter { it.family == family }
                .sortedBy { it.title }
                .toImmutableList()

            if (groupCapabilities.isEmpty()) {
                null
            } else {
                SystemAccessCapabilityGroupUi(
                    family = family,
                    capabilities = groupCapabilities,
                )
            }
        }.toImmutableList()

        _uiState.value = UiState.Success(
            SystemAccessDashboardUiState(
                flavor = environment.flavor,
                groups = groups,
                grantedCount = capabilities.count { it.isUsableForTools },
                visibleCount = capabilities.size,
            )
        )
    }

    private val SystemAccessCapability.family: SystemAccessCapabilityFamily
        get() = when (id) {
            SystemAccessCapabilityIds.APP_PRIVATE_STORAGE,
            SystemAccessCapabilityIds.SAF_STORAGE,
            SystemAccessCapabilityIds.MEDIA_LIBRARY,
            SystemAccessCapabilityIds.ALL_FILES_STORAGE,
            -> SystemAccessCapabilityFamily.Storage

            SystemAccessCapabilityIds.CONTACTS_READ,
            SystemAccessCapabilityIds.CONTACTS_WRITE,
            SystemAccessCapabilityIds.POST_NOTIFICATIONS,
            -> SystemAccessCapabilityFamily.PersonalData

            SystemAccessCapabilityIds.OVERLAY,
            SystemAccessCapabilityIds.NOTIFICATION_LISTENER,
            SystemAccessCapabilityIds.ACCESSIBILITY_SERVICE,
            -> SystemAccessCapabilityFamily.CrossAppFramework

            SystemAccessCapabilityIds.LOCAL_SHELL,
            SystemAccessCapabilityIds.SHIZUKU_BRIDGE,
            SystemAccessCapabilityIds.SUI_BRIDGE,
            -> SystemAccessCapabilityFamily.ShellDelegatedPrivilege

            SystemAccessCapabilityIds.ROOT_SHELL,
            SystemAccessCapabilityIds.ROOT_FILESYSTEM,
            SystemAccessCapabilityIds.ROOT_PROFILE_GUIDANCE,
            -> SystemAccessCapabilityFamily.Root

            else -> SystemAccessCapabilityFamily.CrossAppFramework
        }
}
