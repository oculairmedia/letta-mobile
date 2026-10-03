package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement

/** Draws one plugin element inside its frame on the board, in world units. */
typealias PluginElementRenderer = @Composable (view: PluginElementView, chrome: PluginElementChrome) -> Unit

/**
 * The renderers the board draws plugin elements with, by element type prefix (letta-mobile-s416w.4).
 *
 * Core registers none: every element is drawn by the [fallback] card, which needs no plugin code
 * and works offline or with the plugin uninstalled. A shell (a live-view host, later) registers a
 * renderer for the types it can show live with [register] and provides the result through
 * [ProvidePluginElementRenderers]; the longest registered prefix wins, so `ext:acme.charts/` can
 * be overridden for `ext:acme.charts/bar` alone. An element whose renderer gave up is drawn by the
 * fallback card again.
 */
@Immutable
class PluginElementRenderers private constructor(
    private val byPrefix: Map<String, PluginElementRenderer>,
    val fallback: PluginElementRenderer,
) {
    /** The renderer registered for [type] under its longest matching prefix, or null when there is none. */
    fun registeredFor(type: String): PluginElementRenderer? =
        byPrefix.keys.filter(type::startsWith).maxByOrNull(String::length)?.let(byPrefix::getValue)

    /** The renderer [view] is drawn with: the registered one, or the fallback card when none is or it gave up. */
    fun rendererFor(view: PluginElementView): PluginElementRenderer =
        registeredFor(view.element.type)?.takeUnless { view.faulted } ?: fallback

    /**
     * These renderers with [renderer] drawing every element whose type starts with [typePrefix]
     * (`ext:<pluginId>/` for a whole plugin, `ext:<pluginId>/<kind>` for one kind).
     */
    fun register(typePrefix: String, renderer: PluginElementRenderer): PluginElementRenderers {
        require(typePrefix.startsWith(CanvasPluginElement.TYPE_PREFIX) && typePrefix.length > CanvasPluginElement.TYPE_PREFIX.length) {
            "a renderer is registered for an element type prefix such as ext:acme.charts/, not '$typePrefix'"
        }
        return PluginElementRenderers(byPrefix + (typePrefix to renderer), fallback)
    }

    /** The type prefixes that have a renderer of their own. */
    val prefixes: Set<String> get() = byPrefix.keys

    companion object {
        /** What core ships: the fallback card for everything. */
        val Core: PluginElementRenderers = PluginElementRenderers(emptyMap()) { view, chrome -> PluginFallbackCard(view, chrome) }
    }
}

/** The board's plugin element renderers; [PluginElementRenderers.Core] unless a shell provides more. */
val LocalPluginElementRenderers = staticCompositionLocalOf { PluginElementRenderers.Core }

/** [content] with the renderers [registrations] adds to the ones already provided. */
@Composable
fun ProvidePluginElementRenderers(
    registrations: PluginElementRenderers.() -> PluginElementRenderers,
    content: @Composable () -> Unit,
) {
    // Worked out once per base set: a new set on every recomposition would recompose every card.
    val base = LocalPluginElementRenderers.current
    val renderers = remember(base) { base.registrations() }
    CompositionLocalProvider(LocalPluginElementRenderers provides renderers, content = content)
}
