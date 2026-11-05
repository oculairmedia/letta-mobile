package com.letta.mobile.data.shell

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether the app may quit now. [prepare] saves what the person would lose (unsaved notes, even ones
 * no longer on screen) and says whether all of it is safe. When it is not, the first quit is refused
 * so the person can see what is left; asking again quits anyway, as the explicit choice to discard it.
 */
class ShellQuitGuard(private val prepare: suspend () -> Boolean) {
    private val refused = MutableStateFlow(false)

    /** True when the app may exit now. */
    suspend fun requestQuit(): Boolean = prepare() || !refused.compareAndSet(expect = false, update = true)
}
