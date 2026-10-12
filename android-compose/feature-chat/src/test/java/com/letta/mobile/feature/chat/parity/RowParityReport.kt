package com.letta.mobile.feature.chat.parity

import java.io.File
import java.util.Locale

/** One row's outcome under one theme, as a results-<theme>.tsv line. */
internal data class ParityResult(
    val theme: String,
    val rowId: String,
    val label: String,
    val diffPercent: Double?,
    val legacyHeightDp: Int?,
    val sharedHeightDp: Int?,
    val note: String = "",
) {
    fun toTsv(): String = listOf(
        theme,
        rowId,
        label,
        diffPercent?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
        legacyHeightDp?.toString().orEmpty(),
        sharedHeightDp?.toString().orEmpty(),
        note.replace('\t', ' ').replace('\n', ' '),
    ).joinToString("\t")

    companion object {
        private const val FIELDS = 7

        fun parse(line: String): ParityResult? {
            val f = line.split('\t')
            if (f.size < FIELDS) return null
            return ParityResult(f[0], f[1], f[2], f[3].toDoubleOrNull(), f[4].toIntOrNull(), f[5].toIntOrNull(), f[6])
        }
    }
}

/**
 * The harness's summary: row type -> pixel diff % per theme, with each side's content height. Each
 * theme's test writes its own results file and then rebuilds report.md from every results file in
 * the directory, so the report is whole after whichever test runs last.
 */
internal object RowParityReport {
    const val FILE_NAME = "report.md"

    fun writeResults(dir: File, theme: String, results: List<ParityResult>) {
        dir.mkdirs()
        dir.resolve("results-$theme.tsv").writeText(results.joinToString("\n") { it.toTsv() } + "\n")
    }

    fun rebuild(dir: File, order: List<String>): String {
        val results = dir.listFiles { f -> f.name.startsWith("results-") && f.name.endsWith(".tsv") }
            .orEmpty()
            .flatMap { it.readLines().mapNotNull(ParityResult::parse) }
        val themes = results.map { it.theme }.distinct().sorted()
        val byRow = results.groupBy { it.rowId }
        val rows = order.filter { it in byRow } + byRow.keys.filterNot { it in order }
        val markdown = buildString {
            appendLine("# Legacy vs shared chat row parity (ckmub.1)")
            appendLine()
            appendLine(
                "Pixel diff % per row type: each side cropped to its own content, top-aligned, a pixel " +
                    "differs when any channel moves by more than ${RowParityImages.CHANNEL_TOLERANCE}/255. " +
                    "Phone width (412dp, xhdpi). Heights are each side's content height in dp. " +
                    "Images: `<theme>/<row>.{legacy,shared,diff,side-by-side}.png`.",
            )
            appendLine()
            append("| Row type |")
            themes.forEach { append(" $it diff % | $it legacy/shared dp |") }
            appendLine(" Notes |")
            append("|---|")
            themes.forEach { _ -> append("---:|---:|") }
            appendLine("---|")
            for (rowId in rows) {
                val perTheme = byRow.getValue(rowId).associateBy { it.theme }
                val label = perTheme.values.first().label
                append("| $label (`$rowId`) |")
                themes.forEach { theme ->
                    val r = perTheme[theme]
                    val pct = r?.diffPercent?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"
                    val heights = if (r?.legacyHeightDp != null && r.sharedHeightDp != null) {
                        "${r.legacyHeightDp}/${r.sharedHeightDp}"
                    } else {
                        "n/a"
                    }
                    append(" $pct | $heights |")
                }
                val notes = perTheme.values.map { it.note }.filter { it.isNotBlank() }.distinct().joinToString("; ")
                appendLine(" $notes |")
            }
        }
        dir.resolve(FILE_NAME).writeText(markdown)
        return markdown
    }
}
