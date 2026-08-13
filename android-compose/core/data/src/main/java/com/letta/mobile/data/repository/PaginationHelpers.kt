package com.letta.mobile.data.repository

/**
 * Pagination helpers used by repositories that need to "fetch everything" from a
 * paginated admin API without hardcoding `limit = 1000`.
 *
 * Two flavors are exposed because the admin API mixes two pagination styles:
 *  - **Offset** (`limit` + `offset`) on `Agent`, `Block.listAllBlocks`,
 *    `McpServer`, `Tool`.
 *  - **Cursor** (`limit` + `before`/`after`) on every other affected endpoint
 *    (Archive, Folder, Group, Identity, Provider, Message batches).
 *
 * Both helpers bound the page count via `maxPages` and dedup by a caller-supplied
 * key so a server that ignored `offset` / `after` (returning the same page twice)
 * cannot spin forever. They return early when a short page arrives so the
 * common case (one or two pages of real data) finishes quickly.
 *
 * See `docs/data-efficiency-audit.artifact.md` section 2.2 (Q3) for context.
 */
object PaginationConstants {
    /** Default page size when the underlying API does not impose one. */
    const val DEFAULT_PAGE_SIZE: Int = 50

    /** Default hard cap on pages fetched per call. 50 * 100 = 5_000 records max. */
    const val DEFAULT_MAX_PAGES: Int = 100

    /** Bounded cap for "in-conversation" listings (messages, passages, files, batches). */
    const val BOUNDED_MAX_PAGES: Int = 20
}

/**
 * Exhaust-loop pagination for offset-based APIs (e.g. `Block.listAllBlocks`).
 *
 * Stops early when a page returns fewer than [pageSize] items (which signals
 * "last page"). Caps at [maxPages] iterations as a safety net against a server
 * that ignores `offset` and re-serves the same page -- the [dedupKey] guard
 * will also detect that case and break, but the cap is belt-and-braces.
 *
 * @param pageSize rows to request per call.
 * @param maxPages hard cap on iterations.
 * @param fetch caller-provided page fetch; receives `(limit, offset)`.
 * @param dedupKey stable identity for [T] so we can drop duplicates if a server
 *                 ignores `offset` and re-serves page 1.
 */
suspend fun <T> exhaustPages(
    pageSize: Int = PaginationConstants.DEFAULT_PAGE_SIZE,
    maxPages: Int = PaginationConstants.DEFAULT_MAX_PAGES,
    fetch: suspend (limit: Int, offset: Int) -> List<T>,
    dedupKey: (T) -> Any,
): List<T> {
    require(pageSize > 0) { "pageSize must be > 0, was $pageSize" }
    require(maxPages > 0) { "maxPages must be > 0, was $maxPages" }

    val merged = mutableListOf<T>()
    val seen = HashSet<Any>()
    var iterations = 0
    while (iterations < maxPages) {
        iterations++
        val offset = (iterations - 1) * pageSize
        val page = fetch(pageSize, offset)
        if (page.isEmpty()) return merged
        val fresh = page.filter { seen.add(dedupKey(it)) }
        // Server ignored offset / returned an already-seen page -- stop rather than spin.
        if (fresh.isEmpty()) return merged
        merged += fresh
        if (page.size < pageSize) return merged
    }
    return merged
}

/**
 * Exhaust-loop pagination for cursor-based APIs (e.g. `listGroupMessages`,
 * `listFolderPassages`, `listBatches`). The server returns opaque cursors via
 * `before`/`after`; we thread the last-seen cursor into the next request.
 *
 * - **First call** passes `after = null`.
 * - **Subsequent calls** pass `after = extractCursor(lastItem)`.
 * - **Stop conditions**: empty page, page shorter than [pageSize], or a page
 *   containing only items we've already seen (a server that ignored `after`).
 *
 * @param pageSize rows to request per call.
 * @param maxPages hard cap on iterations.
 * @param fetch caller-provided page fetch; receives `(limit, after)`.
 * @param extractCursor pull the next-page cursor (typically the last item's id).
 * @param dedupKey stable identity for [T] so we can drop duplicates.
 */
suspend fun <T> exhaustCursorPages(
    pageSize: Int = PaginationConstants.DEFAULT_PAGE_SIZE,
    maxPages: Int = PaginationConstants.BOUNDED_MAX_PAGES,
    fetch: suspend (limit: Int, after: String?) -> List<T>,
    extractCursor: (T) -> String,
    dedupKey: (T) -> Any,
): List<T> {
    require(pageSize > 0) { "pageSize must be > 0, was $pageSize" }
    require(maxPages > 0) { "maxPages must be > 0, was $maxPages" }

    val merged = mutableListOf<T>()
    val seen = HashSet<Any>()
    var after: String? = null
    var iterations = 0
    while (iterations < maxPages) {
        iterations++
        val page = fetch(pageSize, after)
        if (page.isEmpty()) return merged
        val fresh = page.filter { seen.add(dedupKey(it)) }
        // Server ignored `after` / returned an already-seen page -- stop rather than spin.
        if (fresh.isEmpty()) return merged
        merged += fresh
        if (page.size < pageSize) return merged
        after = extractCursor(page.last())
    }
    return merged
}