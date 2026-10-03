package com.letta.mobile.ui.canvas.plugin

/** Test tags of plugin elements on the board, by element id (letta-mobile-s416w.4). */
object CanvasPluginTestTags {
    /** The placed box of an element: its frame on the board, scaled by the zoom. */
    fun element(id: String): String = "canvas-plugin-element:$id"

    /** The fallback card drawn inside it. */
    fun card(id: String): String = "canvas-plugin-card:$id"

    /** The live page a [PluginViewHost] shows in place of the card (letta-mobile-s416w.13). */
    fun live(id: String): String = "canvas-plugin-live:$id"

    /** The handle bar an element is dragged by. */
    fun handle(id: String): String = "canvas-plugin-handle:$id"

    fun snapshot(id: String): String = "canvas-plugin-snapshot:$id"

    fun skeleton(id: String): String = "canvas-plugin-skeleton:$id"

    fun placeholder(id: String): String = "canvas-plugin-placeholder:$id"

    fun status(id: String): String = "canvas-plugin-status:$id"

    fun open(id: String): String = "canvas-plugin-open:$id"

    fun badge(id: String, badge: PluginCardBadge): String = "canvas-plugin-badge:$id:${badge.name.lowercase()}"
}
