package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.io.IOException
import java.nio.file.Path

/** Issues the host's epoch: one number persisted in the journal directory, so it only grows across restarts. */
internal object FeedEpochs {
    private const val EPOCH_FILE = "epoch"
    private const val TAG = "FeedJournal"

    /**
     * A new epoch for the host start whose clock reads [now]: above every epoch an earlier start issued (persisted in
     * [directory]) and never below [now]. A null [directory] (memory only) issues [now].
     */
    fun issue(directory: Path?, now: Long): IssuedEpoch {
        val dir = directory ?: return IssuedEpoch(FeedEpoch(now), persisted = true)
        val file = dir.resolve(EPOCH_FILE)
        val issued = maxOf(previous(file) + 1, now)
        val persisted = try {
            AtomicJsonFile.write(file, issued.toString())
            true
        } catch (expected: IOException) {
            Telemetry.event(TAG, "epoch.unwritable", "class" attr expected::class.simpleName, level = Telemetry.Level.WARN)
            false
        }
        return IssuedEpoch(FeedEpoch(issued), persisted)
    }

    private fun previous(file: Path): Long = try {
        AtomicJsonFile.readOrNull(file)?.trim()?.toLongOrNull() ?: 0
    } catch (expected: IOException) {
        Telemetry.event(TAG, "epoch.unreadable", "class" attr expected::class.simpleName, level = Telemetry.Level.WARN)
        0
    }
}

/**
 * An epoch and whether it is on disk. A memory-only host has no earlier start to collide with, so its epoch counts as
 * persisted; one whose write failed could be handed out again by a later start, so a feed must not be served under it.
 */
internal class IssuedEpoch(val epoch: FeedEpoch, val persisted: Boolean)
