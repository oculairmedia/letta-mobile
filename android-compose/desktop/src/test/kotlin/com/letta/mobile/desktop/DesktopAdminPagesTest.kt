package com.letta.mobile.desktop

import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.runs.RunMonitorController
import com.letta.mobile.data.templates.AgentTemplatesController
import com.letta.mobile.desktop.data.unavailableRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.45: a direct App Server desktop session binds the Runs page to the
 * `unavailableRepository()` stub and the Templates page to a repository with no create route. Both
 * pages must say so instead of failing an action (or crashing) when the user reaches for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAdminPagesTest {
    @Test
    fun theRunsPageOverTheUnavailableStubShowsWhyNotAnEmptyList() = runTest {
        val controller = RunMonitorController(unavailableRepository<IRunRepository>(), backgroundScope, ::desktopAdminErrorMessage)
        controller.start()
        runCurrent()
        val state = controller.state.value
        assertEquals("This backend does not serve this page yet.", state.error)
        assertFalse(state.loading)
        assertTrue(state.runs.isEmpty())
        assertFalse(controller.canManageRuns)
    }

    @Test
    fun theTemplatesPageOverTheUnavailableStubOffersNoCreate() = runTest {
        val controller = AgentTemplatesController(
            agentRepository = unavailableRepository<IAgentRepository>(),
            scope = backgroundScope,
            errorMessageMapper = ::desktopAdminErrorMessage,
        )
        assertEquals("This backend cannot create agents yet.", controller.state.value.createUnavailableReason)
        val opened = mutableListOf<String>()
        controller.create("default") { opened += it }
        runCurrent()
        assertTrue(opened.isEmpty())
        assertNull(controller.state.value.error)
        assertNull(controller.state.value.creatingId)
    }
}
