package com.letta.mobile.ui.chat.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * letta-mobile-by4vm.98: a chat host's working-directory extension point, read by a session port
 * for the composer chip and the `workingDirectory` capability. [current] is null where the
 * host cannot read or change one (no chip, capability off).
 */
interface ChatWorkingDirectorySource {
    val current: StateFlow<ChatWorkingDirectoryUiState?>

    fun changeWorkingDirectory(directory: ChatWorkingDirectory)
}

/** The runtime calls behind one conversation's working directory. */
interface ChatWorkingDirectoryTarget {
    /** The directory in effect, or null when it could not be read. */
    suspend fun read(): String?

    /** Moves the conversation to [path]; true once the runtime accepted it. */
    suspend fun write(path: String): Boolean
}

/**
 * The shared [ChatWorkingDirectorySource]: loading while a read or a write is out, the path kept
 * when a write fails. A host says whether it supports a working directory at all ([bindSupport],
 * e.g. on a gateway change); [resolve] names the conversation's target at the moment of a read or
 * a write (null: no conversation, or no support).
 */
class ChatWorkingDirectoryState(
    private val scope: CoroutineScope,
    private val resolve: () -> ChatWorkingDirectoryTarget?,
) : ChatWorkingDirectorySource {
    private val facts = MutableStateFlow(Facts())
    private val state = MutableStateFlow<ChatWorkingDirectoryUiState?>(null)

    override val current: StateFlow<ChatWorkingDirectoryUiState?> = state.asStateFlow()

    /** Whether the host can report and change a working directory now; off hides the chip. */
    fun bindSupport(supports: Boolean) = publish { it.copy(supported = supports) }

    /** Re-reads the directory of the conversation [resolve] names now; none shows no path. */
    fun refresh() {
        val target = resolve() ?: return publish { it.copy(path = null) }
        scope.launch {
            whileLoading {
                val path = quietly(null) { target.read() }
                publish { it.copy(path = path) }
            }
        }
    }

    override fun changeWorkingDirectory(directory: ChatWorkingDirectory) {
        val target = resolve() ?: return
        scope.launch {
            whileLoading {
                if (quietly(false) { target.write(directory.path) }) publish { it.copy(path = directory.path) }
            }
        }
    }

    private suspend fun whileLoading(block: suspend () -> Unit) {
        publish { it.copy(loading = true) }
        try {
            block()
        } finally {
            publish { it.copy(loading = false) }
        }
    }

    private fun publish(transform: (Facts) -> Facts) {
        val next = facts.updateAndGet(transform)
        state.value = if (next.supported) ChatWorkingDirectoryUiState(path = next.path, isLoading = next.loading) else null
    }

    private data class Facts(val supported: Boolean = false, val path: String? = null, val loading: Boolean = false)
}

/** [block]'s answer, or [fallback] when the runtime call failed (a failed read shows no path). */
private suspend fun <T> quietly(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    fallback
}
