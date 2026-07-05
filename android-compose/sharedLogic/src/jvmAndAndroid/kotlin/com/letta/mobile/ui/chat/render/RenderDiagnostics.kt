package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic

/**
 * letta-mobile-x1xnl: flag-gated per-frame chat RENDER diagnostics.
 *
 * The stranded-duplicate assistant row was proven NOT to be a data/reducer/
 * projection bug (server has 1 message, uiProjection.snapshot shows
 * eventsTotal == messageCount, the reducer dedupes every same-otid frame). With
 * a byte-correct message list the LazyColumn still DRAWS one row twice — a
 * Compose recomposition / slot-reuse artifact around the projector's
 * fastPath<->fullPath flip during streaming.
 *
 * These probes make that observable:
 *  - [onRenderItemsBuilt]: every time the render-item list is (re)built, log its
 *    identity hash, size, path (fast/full), and the ordered keys. Two DIFFERENT
 *    list instances presented to the LazyColumn within one streaming frame is
 *    the suspected trigger; the identity hash + key sequence expose it.
 *  - [onLazyItemComposed]: each LazyColumn item() composition logs its key and a
 *    per-render-generation seen-set. If the SAME key is composed twice within
 *    one generation, that is the phantom double-draw — logged as a WARN so it is
 *    greppable even when the rest is at DEBUG.
 *
 * All gated behind [Telemetry.isRenderDiagEnabled] (flag or the LettaRenderDiag
 * logcat tag at VERBOSE), so it is zero-cost when off and easy to turn off once
 * the bug is found. Left in the tree for future render-layer diagnosis.
 */
object RenderDiagnostics {
    private val renderGeneration = atomic(0L)

    // Per-generation set of composed keys, to catch a key composed twice in one
    // pass. Guarded by the atomic generation; cleared when a new list is built.
    private val composedKeysThisGeneration = HashSet<String>()
    private val lock = Any()

    fun enabled(): Boolean = Telemetry.isRenderDiagEnabled()

    /**
     * Call once whenever the render-item list is (re)built for the chat list.
     * Bumps the render generation and logs the list shape + keys.
     */
    fun onRenderItemsBuilt(
        conversationId: String,
        path: String,
        items: List<ChatRenderItem>,
    ) {
        if (!enabled()) return
        val gen = renderGeneration.incrementAndGet()
        synchronized(lock) { composedKeysThisGeneration.clear() }
        val keys = items.joinToString(",") { it.key }
        val dupKeys = items.groupingBy { it.key }.eachCount().filter { it.value > 1 }.keys
        // letta-mobile-x1xnl: detect items that render the SAME assistant content
        // under DIFFERENT keys (the real dupe class — a streaming row vs its
        // reconciled final with a fresh id). Map each item to (runId, contentSig)
        // and flag any group that appears more than once. This distinguishes a
        // genuine content dupe from benign LazyColumn re-composition.
        val contentGroups = HashMap<String, MutableList<String>>()
        for (item in items) {
            val (runId, text) = when (item) {
                is ChatRenderItem.Single -> (item.message.runId ?: "") to item.message.content
                is ChatRenderItem.RunBlock -> item.runId to item.messages.joinToString("") { it.first.content }
            }
            if (item is ChatRenderItem.Single && item.message.role != "assistant") continue
            val sig = "run=$runId|len=${text.length}|head=${text.take(24)}"
            contentGroups.getOrPut(sig) { mutableListOf() }.add(item.key)
        }
        val contentDupes = contentGroups.filter { it.value.size > 1 }
        Telemetry.event(
            "RenderDiag", "renderItems.built",
            "conversationId" to conversationId,
            "generation" to gen,
            "path" to path,
            "listIdentity" to System.identityHashCode(items),
            "count" to items.size,
            "duplicateKeys" to dupKeys.joinToString("|").ifEmpty { "<none>" },
            "contentDupes" to contentDupes.entries.joinToString(" ; ") { "${it.key} -> [${it.value.joinToString(",")}]" }.ifEmpty { "<none>" },
            "keys" to keys.take(300),
            level = if (dupKeys.isNotEmpty() || contentDupes.isNotEmpty()) Telemetry.Level.WARN else Telemetry.Level.DEBUG,
        )
    }

    /**
     * Call inside each LazyColumn item() body. If [key] was already composed in
     * the current render generation, that is the phantom double-draw.
     */
    /**
     * letta-mobile-h30cy: capture the ACTUAL displayed text of an assistant row at
     * a render site (streamed StreamingMarkdownText vs final MarkdownText), so the
     * stream-vs-final punctuation mangle is observable ON DEVICE without a human
     * eyeballing the screen. Flag-gated (LettaRenderDiag VERBOSE); zero-cost off.
     */
    fun onDisplayedText(conversationId: String, site: String, serverId: String, text: String) {
        if (!enabled()) return
        Telemetry.event(
            "RenderDiag", "displayedText",
            "conversationId" to conversationId,
            "site" to site,
            "serverId" to serverId,
            "len" to text.length,
            "head" to text.take(160).replace("\n", "\\n"),
        )
    }

    fun onLazyItemComposed(conversationId: String, key: String, contentType: String) {
        if (!enabled()) return
        val gen = renderGeneration.value
        val doubled = synchronized(lock) { !composedKeysThisGeneration.add(key) }
        Telemetry.event(
            "RenderDiag", if (doubled) "lazyItem.doubleComposed" else "lazyItem.composed",
            "conversationId" to conversationId,
            "generation" to gen,
            "key" to key,
            "contentType" to contentType,
            "doubled" to doubled,
            level = if (doubled) Telemetry.Level.WARN else Telemetry.Level.DEBUG,
        )
    }
}
