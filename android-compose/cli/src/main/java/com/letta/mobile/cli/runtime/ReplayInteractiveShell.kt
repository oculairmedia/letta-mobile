package com.letta.mobile.cli.runtime

import com.letta.mobile.data.timeline.headless.HeadlessTimelineReplaySession
import com.letta.mobile.data.timeline.headless.TimelineAssertionOptions
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ReplayInteractiveShell(
    private val recording: Path,
    private val conversationId: String,
    private val defaultAssertionOptions: TimelineAssertionOptions = TimelineAssertionOptions(),
) {
    private var reader: BufferedReader? = null
    private var iterator: Iterator<String> = emptyList<String>().iterator()
    private var session = newSession()
    private val fixtureLines = mutableListOf<String>()
    private var previousTimelineJson: String? = null
    private var currentTimelineJson: String? = null

    suspend fun run() {
        resetReader()
        println("[replay] interactive session started; type help for commands")
        try {
            while (true) {
                print("replay> ")
                val line = readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (!handle(trimmed)) break
            }
        } finally {
            reader?.close()
        }
    }

    private suspend fun handle(commandLine: String): Boolean {
        val command = commandLine.substringBefore(" ")
        val args = commandLine.substringAfter(" ", missingDelimiterValue = "").trim()
        when (command) {
            "help", "?" -> printHelp()
            "step" -> step(args.toIntOrNull() ?: 1)
            "dump" -> println(session.dumpJson())
            "diff" -> printDiff()
            "inject" -> inject(args)
            "assert" -> runAssertion(args)
            "save-fixture" -> saveFixture(args)
            "reset" -> reset()
            "exit", "quit" -> return false
            else -> println("[replay] unknown command: $command")
        }
        return true
    }

    private suspend fun step(count: Int) {
        if (count < 1) {
            println("[replay] step count must be >= 1")
            return
        }
        repeat(count) {
            val line = nextNonBlankLine()
            if (line == null) {
                println("[replay] end of recording")
                return
            }
            ingest(line, synthetic = false)
        }
    }

    private suspend fun inject(jsonLine: String) {
        if (jsonLine.isBlank()) {
            println("[replay] inject requires a JSON frame")
            return
        }
        ingest(jsonLine, synthetic = true)
    }

    private suspend fun ingest(line: String, synthetic: Boolean) {
        previousTimelineJson = currentTimelineJson
        val step = session.ingestLine(line, captureTimeline = true)
        if (step == null) return
        fixtureLines += line.trim()
        currentTimelineJson = step.snapshot?.timeline?.let { prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }
        val source = if (synthetic) "synthetic" else "frame"
        val status = if (step.ingested) "ingested" else "ignored:${step.ignoredReason}"
        println("[$source ${step.frameIndex}] ${step.frameType} ${step.frameId ?: "<no-id>"} $status")
        println("  Timeline: ${timelineSummary(currentTimelineJson)}")
    }

    private suspend fun runAssertion(name: String) {
        val options = name.toAssertionOptions()
        if (options == null) {
            println("[replay] unknown assertion: $name")
            return
        }
        val report = session.assertTimeline(options)
        if (report.passed) {
            println("[replay] PASS")
        } else {
            report.failures.forEach { println("[replay] FAIL $it") }
        }
    }

    private fun saveFixture(pathArg: String) {
        if (pathArg.isBlank()) {
            println("[replay] save-fixture requires a path")
            return
        }
        val path = Path.of(pathArg)
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, fixtureLines, StandardCharsets.UTF_8)
        println("[replay] saved ${fixtureLines.size} frames to $path")
    }

    private suspend fun reset() {
        reader?.close()
        session = newSession()
        fixtureLines.clear()
        previousTimelineJson = null
        currentTimelineJson = null
        resetReader()
        println("[replay] reset")
    }

    private fun resetReader() {
        reader = Files.newBufferedReader(recording)
        iterator = reader!!.lineSequence().iterator()
    }

    private fun nextNonBlankLine(): String? {
        while (iterator.hasNext()) {
            val line = iterator.next().trim()
            if (line.isNotEmpty()) return line
        }
        return null
    }

    private fun newSession(): HeadlessTimelineReplaySession =
        HeadlessTimelineReplaySession(conversationId = conversationId)

    private fun printDiff() {
        val previous = previousTimelineJson
        val current = currentTimelineJson
        when {
            previous == null || current == null -> println("[diff] no previous frame")
            previous == current -> println("[diff] no timeline changes")
            else -> {
                println("[diff] ${timelineSummary(previous)} -> ${timelineSummary(current)}")
                val previousLines = previous.lines()
                val currentLines = current.lines()
                val firstDifferent = previousLines.zip(currentLines)
                    .indexOfFirst { (left, right) -> left != right }
                    .takeIf { it >= 0 } ?: minOf(previousLines.size, currentLines.size)
                previousLines.getOrNull(firstDifferent)?.let { println("- $it") }
                currentLines.getOrNull(firstDifferent)?.let { println("+ $it") }
            }
        }
    }

    private fun printHelp() {
        println(
            """
            Commands:
              step [N]                       ingest the next N recorded frames
              dump                           print full Timeline JSON
              diff                           print the first Timeline JSON difference from the previous frame
              inject <json>                  ingest a synthetic frame through the replay path
              assert <name>                  run an assertion against current state
              save-fixture <path>            write consumed + injected frames as JSONL
              reset                          restart from frame 0
              exit                           quit

            Assertions:
              default, no-dups, otid-unique, seq-monotonic, no-empty-bodies,
              no-prefix-orphans, ui-message-count-per-run=N,
              final-status-matches=completed|cancelled|failed, no-orphan-tool-returns
            """.trimIndent()
        )
    }

    private fun timelineSummary(timelineJson: String?): String {
        if (timelineJson == null) return "<unavailable>"
        val root = runCatching { json.parseToJsonElement(timelineJson).jsonObject }.getOrNull()
            ?: return "<invalid timeline>"
        val eventCount = root["eventCount"]?.jsonPrimitive?.contentOrNull ?: "?"
        val liveCursor = root["liveCursor"]?.jsonPrimitive?.contentOrNull ?: "<null>"
        return "$eventCount events, liveCursor=$liveCursor"
    }

    private fun String.toAssertionOptions(): TimelineAssertionOptions? {
        val normalized = trim()
        if (normalized.isBlank() || normalized == "default") return defaultAssertionOptions
        return when {
            normalized == "no-dups" -> TimelineAssertionOptions(assertNoDuplicateUiMessages = true)
            normalized == "otid-unique" -> TimelineAssertionOptions(assertOtidUnique = true)
            normalized == "seq-monotonic" -> TimelineAssertionOptions(assertSeqMonotonic = true)
            normalized == "no-empty-bodies" -> TimelineAssertionOptions(assertNoEmptyBodies = true)
            normalized == "no-prefix-orphans" -> TimelineAssertionOptions(assertNoPrefixOrphans = true)
            normalized == "no-orphan-tool-returns" -> TimelineAssertionOptions(assertNoOrphanToolReturns = true)
            normalized.startsWith("ui-message-count-per-run=") -> {
                val expected = normalized.substringAfter("=").toIntOrNull()?.takeIf { it > 0 }
                    ?: return null
                TimelineAssertionOptions(expectedUiMessageCountPerRun = expected)
            }
            normalized.startsWith("final-status-matches=") -> {
                val expected = normalized.substringAfter("=").lowercase()
                if (expected !in setOf("completed", "cancelled", "failed")) return null
                TimelineAssertionOptions(expectedFinalStatus = expected)
            }
            else -> null
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        val prettyJson = Json {
            prettyPrint = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
