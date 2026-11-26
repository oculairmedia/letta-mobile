package com.letta.mobile.data.node.info

import kotlinx.coroutines.flow.MutableStateFlow

/** Where a node reads the runtime patch set its App Server applied. */
fun interface RuntimePatchSetSource {
    /** The set as last seen; [RuntimePatchSet.UNKNOWN] when there is none. */
    fun current(): RuntimePatchSet

    companion object {
        /** A node with no view of its App Server's boot output. */
        val Unknown: RuntimePatchSetSource = RuntimePatchSetSource { RuntimePatchSet.UNKNOWN }
    }
}

/** Parses the patch loader's boot summary, `lcp-patches applied=N skipped=M`. */
object RuntimePatchBootLine {
    private val SUMMARY = Regex("""\blcp-patches applied=(\d{1,6}) skipped=(\d{1,6})\b""")

    /** The set [line] reports, or null when it is not the boot summary. */
    fun parse(line: String): RuntimePatchSet? = SUMMARY.find(line)?.let { match ->
        RuntimePatchSet(
            source = RuntimePatchSet.SOURCE_BOOT_LINE,
            applied = match.groupValues[1].toInt(),
            skipped = match.groupValues[2].toInt(),
        )
    }
}

/**
 * Follows a supervised App Server's output lines and keeps the last boot summary it saw. A host that
 * reads its App Server's stderr feeds every line to [observe]; a restart's new summary replaces the old.
 */
class RuntimePatchBootLineObserver : RuntimePatchSetSource {
    private val last = MutableStateFlow(RuntimePatchSet.UNKNOWN)

    /** Records [line] when it is the patch loader's boot summary; ignores any other line. */
    fun observe(line: String) {
        RuntimePatchBootLine.parse(line)?.let { last.value = it }
    }

    override fun current(): RuntimePatchSet = last.value
}
