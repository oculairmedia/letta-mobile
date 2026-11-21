package com.letta.mobile.data.controller.node.iroh.feed

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest

/** What the journal store tests share: scratch directories, a clock that moves one second per host start, and the one journal file. */
abstract class FeedJournalTestBase {
    private val directories = mutableListOf<Path>()

    private var now = 10_000L

    protected fun directory(): Path = createTempDirectory("feed-journal").also { directories.add(it) }

    /** The clock of one more host start: every call is later than the last, so every start issues a greater epoch. */
    protected fun nextClock(): Long = now++ * 1000

    /** One host start over [directory]; a later call is a wrapper restart (a later clock, a new epoch). */
    protected fun start(directory: Path?, retention: FeedRetention = FeedRetention()): FeedJournalStore =
        FeedJournalStore(directory, retention) { nextClock() }

    protected fun fileOf(dir: Path): Path = dir.listDirectoryEntries("*.jsonl").single()

    @AfterTest
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
    }
}
