package com.letta.mobile.ui.screens.canvas

import com.letta.mobile.data.canvas.library.CanvasBoardOwner
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Provider

/** Binds shared board ownership to the Android retained-window lifecycle. */
@ActivityRetainedScoped
class AndroidCanvasBoards @Inject constructor(
    factory: Provider<CanvasBoardSessions>,
    lifecycle: ActivityRetainedLifecycle,
) : CanvasBoardOwner(factory::get) {
    init { lifecycle.addOnClearedListener(::close) }
}
