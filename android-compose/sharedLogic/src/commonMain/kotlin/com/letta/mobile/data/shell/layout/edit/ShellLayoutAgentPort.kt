package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who changed the shell layout. */
sealed interface ShellLayoutAuthor {
    /**
     * An agent, through a tool call.
     *
     * @property agent the agent, when the call said which.
     */
    data class Agent(val agent: AgentId?) : ShellLayoutAuthor

    /** The person, from the app (Reset layout). */
    data object Person : ShellLayoutAuthor
}

/**
 * One change made to the on-screen shell layout through a [ShellLayoutAgentPort].
 *
 * @property author who made it.
 * @property summary what changed, in a short sentence.
 * @property before the layout it replaced.
 * @property after the layout it made.
 * @property undoable false for a change that itself stepped back through the history.
 */
class ShellLayoutChange internal constructor(
    val author: ShellLayoutAuthor,
    val summary: String,
    val before: ShellLayoutConfig,
    val after: ShellLayoutConfig,
    val undoable: Boolean,
    internal val store: ShellLayoutConfigStore,
)

/** What a change through the port came to. */
sealed interface ShellLayoutCommit {
    /**
     * The layout changed; the shell redraws it at once.
     *
     * @property change the change made.
     */
    data class Done(val change: ShellLayoutChange) : ShellLayoutCommit

    /** No shell is on screen, so there is no layout to change. */
    data object NoShell : ShellLayoutCommit

    /** Nothing to do: the layout already is the result, or there is no earlier layout to go back to. */
    data object Unchanged : ShellLayoutCommit

    /** The layout changed after the edit was computed from it; nothing was applied. */
    data object Stale : ShellLayoutCommit
}

/**
 * The app-wide way from outside composition (an agent's tool call) to the shell on screen. A shell
 * attaches its backend's [ShellLayoutConfigStore] while it is shown and detaches it when it goes;
 * with several attached, the newest is the one changed. Every change made through the port is
 * published on [changes] so the shell can tell the person and offer Undo.
 */
class ShellLayoutAgentPort {
    private val lock = SynchronizedObject()
    private var stores: List<ShellLayoutConfigStore> = emptyList()
    private var unseen: ShellLayoutChange? = null
    private val attachedStore = MutableStateFlow<ShellLayoutConfigStore?>(null)
    private val published = MutableSharedFlow<ShellLayoutChange>(
        extraBufferCapacity = CHANGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The store changes go to: the newest attached, or null while no shell is on screen. */
    val attached: StateFlow<ShellLayoutConfigStore?> = attachedStore.asStateFlow()

    /** Every change made through this port, as it is made. */
    val changes: SharedFlow<ShellLayoutChange> = published.asSharedFlow()

    /** The latest unseen change, if it still describes the attached layout, without acknowledging it. */
    fun peekUnseenChange(): ShellLayoutChange? = synchronized(lock) { validUnseenChange() }

    /**
     * Claims the latest unseen change, if it still describes the attached layout. When [expected]
     * is supplied, only that exact change may be claimed. A claim acknowledges delivery; changes
     * are retained in memory only and a later change replaces an unseen one.
     */
    fun claimUnseenChange(expected: ShellLayoutChange? = null): ShellLayoutChange? = synchronized(lock) {
        val change = validUnseenChange() ?: return@synchronized null
        if (expected != null && expected !== change) return@synchronized null
        unseen = null
        change
    }

    private fun validUnseenChange(): ShellLayoutChange? {
        val change = unseen ?: return null
        if (change.store !== attachedStore.value || change.store.config.value != change.after) {
            unseen = null
            return null
        }
        return change
    }

    /** Makes [store] the one changes go to, until [detach]. */
    fun attach(store: ShellLayoutConfigStore) = synchronized(lock) {
        stores = stores - store + store
        attachedStore.value = stores.lastOrNull()
        if (unseen?.store !== attachedStore.value) unseen = null
    }

    /** Stops sending changes to [store]; the store attached before it, if any, takes over. */
    fun detach(store: ShellLayoutConfigStore) = synchronized(lock) {
        stores = stores - store
        attachedStore.value = stores.lastOrNull()
        if (unseen?.store !== attachedStore.value) unseen = null
    }

    /**
     * Makes [after] the layout when it still is [expected], recording the change in the store's
     * history and publishing it on [changes].
     */
    fun commit(author: ShellLayoutAuthor, summary: String, after: ShellLayoutConfig, expected: ShellLayoutConfig): ShellLayoutCommit {
        val store = attachedStore.value ?: return ShellLayoutCommit.NoShell
        if (store.config.value != expected) return ShellLayoutCommit.Stale
        val before = store.commit(after, expected) ?: return if (after.upgraded() == expected) {
            ShellLayoutCommit.Unchanged
        } else {
            ShellLayoutCommit.Stale
        }
        return publish(ShellLayoutChange(author, summary, before, store.config.value, undoable = true, store))
    }

    /** Puts the attached shell back to [ShellLayoutConfig.Default]; undoable like any change. */
    fun reset(author: ShellLayoutAuthor): ShellLayoutCommit {
        val store = attachedStore.value ?: return ShellLayoutCommit.NoShell
        val before = store.resetToDefault() ?: return ShellLayoutCommit.Unchanged
        return publish(ShellLayoutChange(author, "Reset to the default layout", before, store.config.value, undoable = true, store))
    }

    /** Steps the attached shell back one change in its history. */
    fun undoLatest(author: ShellLayoutAuthor): ShellLayoutCommit {
        val store = attachedStore.value ?: return ShellLayoutCommit.NoShell
        val before = store.config.value
        val restored = store.undo() ?: return ShellLayoutCommit.Unchanged
        return publish(ShellLayoutChange(author, "Undid the last layout change", before, restored, undoable = false, store))
    }

    /**
     * Undoes [change] when its layout is still the one shown (a later change has its own Undo).
     *
     * @return true when the layout went back.
     */
    fun undo(change: ShellLayoutChange): Boolean = change.undoable && change.store.undo(ifCurrent = change.after) != null

    private fun publish(change: ShellLayoutChange): ShellLayoutCommit {
        // Emitted under the lock that orders [unseen]: two callers publishing at once cannot deliver
        // their events in the opposite order to the one that decided which change is unseen.
        synchronized(lock) {
            if (change.store === attachedStore.value && change.store.config.value == change.after) unseen = change
            published.tryEmit(change)
        }
        return ShellLayoutCommit.Done(change)
    }

    /** The port the app's shells and tools share. */
    companion object {
        private const val CHANGE_BUFFER = 8

        /** The one port of this process: the shell attaches to it, the layout tool changes through it. */
        val Shared: ShellLayoutAgentPort = ShellLayoutAgentPort()
    }
}
