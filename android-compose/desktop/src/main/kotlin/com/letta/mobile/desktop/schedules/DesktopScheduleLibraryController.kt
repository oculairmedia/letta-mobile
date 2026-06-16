package com.letta.mobile.desktop.schedules

import com.letta.mobile.data.repository.http.LettaHttpAdminRepositoryException
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.desktop.data.DesktopRepositoryUnavailableException
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

typealias DesktopScheduleLibraryState = ScheduleLibraryState

class DesktopScheduleLibraryController(
    sessionGraphProvider: DesktopSessionGraphProvider,
    scope: CoroutineScope,
) : AutoCloseable {
    private val graph = sessionGraphProvider.current
    private val delegate = ScheduleLibraryController(
        agentRepository = graph.agentRepository,
        scheduleRepository = graph.scheduleRepository,
        scope = scope,
        errorMessageMapper = { throwable, fallback -> throwable.toDesktopScheduleMessage(fallback) },
        scheduleAdminUnavailableMatcher = { throwable -> throwable.isScheduleAdminUnavailable() },
    )
    val state: StateFlow<DesktopScheduleLibraryState> = delegate.state

    fun start() {
        delegate.start()
    }

    fun reload() {
        delegate.loadData()
    }

    fun selectAgent(agentId: String) {
        delegate.selectAgent(agentId)
    }

    override fun close() {
        delegate.close()
    }

    private fun Throwable.isScheduleAdminUnavailable(): Boolean =
        this is LettaHttpAdminRepositoryException && code in setOf(404, 405, 501)

    private fun Throwable.toDesktopScheduleMessage(fallback: String): String =
        when (this) {
            is DesktopRepositoryUnavailableException -> "Desktop schedule repositories are not available for this backend yet."
            else -> message ?: this::class.simpleName ?: fallback
        }
}
