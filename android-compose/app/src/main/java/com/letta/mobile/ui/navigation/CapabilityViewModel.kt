package com.letta.mobile.ui.navigation

import androidx.lifecycle.ViewModel
import com.letta.mobile.data.capability.CapabilityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * letta-mobile-2ixd: thin Hilt-injected accessor so [AdaptiveScaffold]
 * (a plain composable, not a screen) can observe
 * [CapabilityRepository.projectsSupported] without taking a direct
 * constructor dependency. The repository is a singleton; this VM just
 * exposes its flows by reference.
 */
@HiltViewModel
class CapabilityViewModel @Inject constructor(
    capabilityRepository: CapabilityRepository,
) : ViewModel() {
    val projectsSupported: StateFlow<Boolean> = capabilityRepository.projectsSupported
}
