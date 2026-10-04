package com.letta.mobile.ui.chat.surface.sendlift

import androidx.compose.foundation.lazy.LazyListState
import java.lang.reflect.Modifier

/**
 * letta-mobile-86njl.1: finds the [LazyListState] a lambda closes over, by walking its captured
 * fields. The page keeps its list state private; the list's scroll-axis semantics capture it. Fails
 * loudly (null) if a Compose upgrade changes the capture, as ScrollCommandProbe does for its flag.
 */
internal object ListStateFinder {
    private const val MAX_DEPTH = 5

    fun within(root: kotlin.Any): LazyListState? = search(root, MAX_DEPTH, mutableSetOf())

    private fun search(node: kotlin.Any, depth: Int, seen: MutableSet<Int>): LazyListState? {
        if (node is LazyListState) return node
        if (depth == 0 || !seen.add(System.identityHashCode(node))) return null
        return capturedFields(node).firstNotNullOfOrNull { search(it, depth - 1, seen) }
    }

    private fun capturedFields(node: kotlin.Any): List<kotlin.Any> =
        node.javaClass.declaredFields
            .filterNot { it.type.isPrimitive || Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> runCatching { field.apply { isAccessible = true }.get(node) }.getOrNull() }
}
