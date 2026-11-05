package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.ui.plugin.RuntimeObjectView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The shell's live view registry (letta-mobile-n2stx.2.1, ADR-2): canvas viewers and neutral object
 * views registered while the app runs, not only when a composition builds its list. Every mount under
 * [ProvideCanvasDocumentViewerRegistry] sees a registration at once and loses it when its handle
 * closes; a mount showing a removed viewer falls back to the built-in canvas.
 *
 * An instance, not an object, for the same reason as the canvas session registry: whoever owns the
 * shell owns one. Registration may come from any thread, so the table is updated by compare-and-set.
 */
@Stable
class CanvasDocumentViewerRegistry(base: CanvasDocumentViewers = CanvasDocumentViewers.Core) {
    internal class Table(val viewers: CanvasDocumentViewers, val owners: Map<RuntimeViewId, Long>, val next: Long)

    private val table = MutableStateFlow(Table(base, emptyMap(), 0L))

    /** Each change, with the views it leaves; one flow, so a reader never sees an older table after a newer one. */
    internal val tables: StateFlow<Table> = table.asStateFlow()

    /** The views registered now. */
    val current: CanvasDocumentViewers get() = table.value.viewers

    /** Adds [viewer], replacing any viewer with its ID; closing the handle removes it. */
    fun register(viewer: CanvasDocumentViewer): CanvasDocumentViewerRegistration =
        add(RuntimeViewId(viewer.id.value)) { it.register(viewer) }

    /** Adds [view], replacing any view with its ID; closing the handle removes it. */
    fun register(view: RuntimeObjectView): CanvasDocumentViewerRegistration =
        add(view.descriptor.id) { it.register(view) }

    private fun add(id: RuntimeViewId, register: (CanvasDocumentViewers) -> CanvasDocumentViewers): CanvasDocumentViewerRegistration {
        val owner = update { current ->
            val token = current.next
            Table(register(current.viewers), current.owners + (id to token), token + 1) to token
        }
        return CanvasDocumentViewerRegistration { remove(id, owner) }
    }

    /** Removes [id] only while [owner] still holds it, so a replaced registration's late close keeps its successor. */
    private fun remove(id: RuntimeViewId, owner: Long) {
        update { current ->
            if (current.owners[id] != owner) current to Unit
            else Table(current.viewers.unregister(id), current.owners - id, current.next) to Unit
        }
    }

    private fun <R> update(transform: (Table) -> Pair<Table, R>): R {
        while (true) {
            val current = table.value
            val (next, result) = transform(current)
            if (table.compareAndSet(current, next)) return result
        }
    }
}

/** One registration's removal. Idempotent: closing twice, or after a replacement, changes nothing more. */
class CanvasDocumentViewerRegistration internal constructor(private val onClose: () -> Unit) {
    private val closed = MutableStateFlow(false)

    fun close() {
        if (closed.compareAndSet(expect = false, update = true)) onClose()
    }
}

/** The shell's registry, for code inside it that contributes views; null outside a shell. */
val LocalCanvasDocumentViewerRegistry = staticCompositionLocalOf<CanvasDocumentViewerRegistry?> { null }

/** Makes [registry]'s current views the ones every viewer host in [content] offers. */
@Composable
fun ProvideCanvasDocumentViewerRegistry(registry: CanvasDocumentViewerRegistry, content: @Composable () -> Unit) {
    val table by registry.tables.collectAsState()
    CompositionLocalProvider(
        LocalCanvasDocumentViewerRegistry provides registry,
        LocalCanvasDocumentViewers provides table.viewers,
        content = content,
    )
}

/**
 * Registers [viewer] while this composition is present: a contribution scoped to the UI that offers it.
 * Pass a remembered viewer; a new instance each composition re-registers on every change it causes.
 */
@Composable
fun RegisterCanvasDocumentViewer(viewer: CanvasDocumentViewer) {
    val registry = LocalCanvasDocumentViewerRegistry.current ?: return
    DisposableEffect(registry, viewer) {
        val registration = registry.register(viewer)
        onDispose(registration::close)
    }
}
