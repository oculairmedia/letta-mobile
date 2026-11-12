package com.letta.mobile.data.hoststate

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private const val OLD = "old-contents"
private const val NEW = "new-contents"

/** Where an injected failure happens relative to the atomic move, and what must follow from it. */
private enum class FaultPoint(val expected: KClass<out DurableReplacement>, val contents: String) {
    /** The directory barrier before the temp file is created. */
    PARENT_SYNC(DurableReplacement.NotApplied::class, OLD),

    /** After the temp file is forced, before the move. */
    BEFORE_MOVE(DurableReplacement.NotApplied::class, OLD),

    /** Immediately after the move. */
    AFTER_MOVE(DurableReplacement.Unknown::class, NEW),

    /** The directory barrier after the move. */
    DIRECTORY_SYNC(DurableReplacement.Unknown::class, NEW),
}

/** Which directory sync call fails: the first precedes the move, the second follows it. */
@JvmInline
private value class FailingSyncCall(val number: Int)

private fun failingSync(call: FailingSyncCall, failure: IOException): (Path) -> Unit {
    val calls = AtomicInteger()
    return { if (calls.incrementAndGet() == call.number) throw failure }
}

private fun fileFailingAt(path: Path, point: FaultPoint, failure: IOException): DurableHostFile = when (point) {
    FaultPoint.PARENT_SYNC -> DurableHostFile(path, null, failingSync(FailingSyncCall(1), failure))
    FaultPoint.BEFORE_MOVE -> DurableHostFile(path, null, {}, DurableHostFileHooks(beforeMove = { throw failure }))
    FaultPoint.AFTER_MOVE -> DurableHostFile(path, null, {}, DurableHostFileHooks(afterMove = { throw failure }))
    FaultPoint.DIRECTORY_SYNC -> DurableHostFile(path, null, failingSync(FailingSyncCall(2), failure))
}

private fun existingFile(): Path = Files.createTempDirectory("durable-commit-point").resolve("state.json").also {
    Files.writeString(it, OLD)
}

private fun cause(outcome: DurableReplacement): Throwable? = when (outcome) {
    DurableReplacement.Applied -> null
    is DurableReplacement.NotApplied -> outcome.cause
    is DurableReplacement.Unknown -> outcome.cause
}

private fun leftovers(path: Path): List<Path> = Files.list(path.parent).use { entries ->
    entries.filter { it.fileName.toString().endsWith(".tmp") }.toList()
}

class DurableHostFileCommitPointTest {
    @Test
    fun eachFaultPointReportsWhichSideOfTheMoveItFellOn() = runBlocking {
        for (point in FaultPoint.entries) {
            val path = existingFile()
            val failure = IOException("injected at $point")
            val outcome = fileFailingAt(path, point, failure).replace(NEW)
            assertEquals(point.expected to point.contents, outcome::class to Files.readString(path), "$point")
            assertSame(failure, cause(outcome), "$point")
            assertEquals(emptyList<Path>(), leftovers(path), "$point")
        }
    }

    @Test
    fun aCleanReplacementIsApplied() = runBlocking {
        val path = existingFile()
        assertEquals(DurableReplacement.Applied to NEW, DurableHostFile(path) {}.replace(NEW) to Files.readString(path))
    }

    @Test
    fun writeStillThrowsTheOriginalFailureOnEitherSideOfTheMove() {
        for (point in FaultPoint.entries) {
            val path = existingFile()
            val failure = IOException("injected at $point")
            val thrown = assertFailsWith<IOException> { fileFailingAt(path, point, failure).write(NEW) }
            assertSame(failure, thrown, "$point")
            assertEquals(point.contents, Files.readString(path), "$point")
        }
    }
}
