package com.letta.mobile.architecture

import java.io.File

/** A known violation that is tolerated until its owning bead lands. */
internal data class BaselineEntry(val violation: BoundaryViolation, val bead: String)

internal object ArchitectureBoundaryBaseline {
    private val BEAD_ID = Regex("letta-mobile-[a-z0-9]+(\\.[0-9]+)*")

    /** Reads `<rule> <from> -> <to> <owning-bead>` lines; `#` starts a comment. */
    fun read(file: File): List<BaselineEntry> =
        if (file.isFile) parse(file.readLines(), file.path) else emptyList()

    fun parse(lines: List<String>, source: String = "baseline"): List<BaselineEntry> =
        lines.map { it.substringBefore('#').trim() }
            .filter(String::isNotEmpty)
            .map { line -> parseEntry(line, source) }

    private fun parseEntry(line: String, source: String): BaselineEntry {
        val tokens = line.split(Regex("\\s+"))
        if (tokens.size != 5 || tokens[2] != "->") {
            throw ArchitectureGraphException("$source: expected `<rule> <from> -> <to> <owning-bead>`, got `$line`")
        }
        val (rule, from, _, to, bead) = tokens
        if (ArchitectureBoundaryRules.ALL.none { it.id == rule }) {
            throw ArchitectureGraphException("$source: unknown rule `$rule` in `$line`")
        }
        if (!BEAD_ID.matches(bead)) {
            throw ArchitectureGraphException("$source: `$line` needs an owning bead id (letta-mobile-...)")
        }
        return BaselineEntry(BoundaryViolation(rule, from, to), bead)
    }
}

internal data class BoundaryGateResult(
    val newViolations: List<BoundaryViolation>,
    val staleEntries: List<BaselineEntry>,
    val baselined: List<BaselineEntry>,
) {
    val passed: Boolean get() = newViolations.isEmpty() && staleEntries.isEmpty()

    fun report(): String = buildString {
        appendLine("Module-boundary gate: ${if (passed) "PASSED" else "FAILED"}")
        appendSection("New violations (fix the edge; do not baseline new debt)", newViolations.map { it.toString() })
        appendSection(
            "Stale baseline entries (the edge is gone; delete the line and close its bead)",
            staleEntries.map { "${it.violation} ${it.bead}" },
        )
        appendSection("Baselined violations", baselined.map { "${it.violation} ${it.bead}" })
        appendLine()
        ArchitectureBoundaryRules.ALL.forEach { appendLine("rule ${it.id}: ${it.description}") }
    }

    private fun StringBuilder.appendSection(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("$title:")
        lines.forEach { appendLine("  $it") }
    }
}

internal object ArchitectureBoundaryGate {
    fun evaluate(graph: ArchitectureGraph, baseline: List<BaselineEntry>): BoundaryGateResult {
        val violations = ArchitectureBoundaryRules.violations(graph)
        val baselinedViolations = baseline.map(BaselineEntry::violation).toSet()
        return BoundaryGateResult(
            newViolations = violations.filterNot(baselinedViolations::contains),
            staleEntries = baseline.filterNot { it.violation in violations },
            baselined = baseline.filter { it.violation in violations },
        )
    }
}
