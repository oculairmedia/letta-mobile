package com.letta.mobile.ui.screens.canvas

import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.LettaConfig
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One window's board services, retained across Activity recreation but never across windows. */
@ActivityRetainedScoped
class AndroidCanvasBoards @Inject constructor(
    private val factory: Provider<CanvasBoardSessions>,
    lifecycle: ActivityRetainedLifecycle,
) {
    private val current = MutableStateFlow(factory.get())
    private var backend: LettaConfig? = null
    private var resolved = false

    /** The current backend's table and registry; consumers observe replacement without keying navigation. */
    val boards = current.asStateFlow()

    init {
        lifecycle.addOnClearedListener { current.value.clear() }
    }

    /**
     * Uses the same config id as rememberShellLayoutController and ShellLayoutConfigStore.forBackend.
     * The first loaded config adopts the startup owner; edits to that config do not replace it.
     * Later identity changes (including sign-out) retire every old hold and registration.
     */
    fun updateBackend(config: LettaConfig?) {
        if (!resolved) {
            if (config == null) return
            backend = config
            resolved = true
            return
        }
        if (backend?.id == config?.id) return
        backend = config
        current.value.clear()
        current.value = factory.get()
    }
}
