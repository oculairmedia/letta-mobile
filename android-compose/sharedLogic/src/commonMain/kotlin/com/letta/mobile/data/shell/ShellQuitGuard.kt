package com.letta.mobile.data.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the app may quit now. [prepare] saves what the person would lose (unsaved notes, even ones
 * no longer on screen) and says whether all of it is safe. When it is not, the first quit is refused
 * so the person can see what is left; asking again quits anyway, as the explicit choice to discard it.
 * A request made while another is still preparing is refused, so only a Quit sent after a refusal
 * can discard anything. That discarding Quit still tries to save once more, for at most
 * [discardSaveLimit], so a stalled save cannot keep the app open.
 */
class ShellQuitGuard(
    private val discardSaveLimit: Duration = 5.seconds,
    private val prepare: suspend () -> Boolean,
) {
    private val refused = MutableStateFlow(false)
    private val preparing = MutableStateFlow(false)

    /** True when the app may exit now. */
    suspend fun requestQuit(): Boolean {
        if (!preparing.compareAndSet(expect = false, update = true)) return false
        val discard = refused.value
        return try {
            if (discard) {
                withTimeoutOrNull(discardSaveLimit) { prepare() }
                true
            } else {
                prepare() || !refused.compareAndSet(expect = false, update = true)
            }
        } finally {
            preparing.value = false
        }
    }
}
