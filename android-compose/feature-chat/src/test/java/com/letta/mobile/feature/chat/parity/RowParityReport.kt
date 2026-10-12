package com.letta.mobile.feature.chat.parity

import java.io.File
import java.util.Locale

/** One row's outcome under one theme, as a results-<theme>.tsv line. */
internal data class ParityResult(
    val theme: String,
    val rowId: String,
    val label: String,
    val diffPercent: Double?,
    val goldenHeightDp: Int?,
    val sharedHeightDp: Int?,
    val note: String = "",
) {
    fun toTsv(): String = listOf(
        theme,
        rowId,
        label,
        diffPercent?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
        goldenHeightDp?.toString().orEmpty(),
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
 * The goldens' summary: row type -> pixel diff % per theme, with each side's content height. Each
 * theme's test writes its own results file and then rebuilds report.md from every results file in
 * the directory, so the report is whole after whichever test runs last.
 */
internal object RowParityReport {
    const val FILE_NAME = "report.md"

    fun writeResults(dir: File, theme: String, results: List<ParityResult>) {
        dir.mkdirs()
        dir.resolve("results-$theme.tsv").writeText(results.joinToString("\n") { it.toTsv() } + "\n")
    }

    private const val NOT_AVAILABLE = "n/a"

    private val INTRO =
        "Pixel diff % per row type against the committed golden: each side cropped to its own content, top-aligned, a pixel " +
            "differs when any channel moves by more than ${RowParityImages.CHANNEL_TOLERANCE}/255. " +
            "Phone width (412dp, xhdpi). Heights are each side's content height in dp. " +
            "Images: `<theme>/<row>.{golden,shared,diff,side-by-side}.png`."

    fun rebuild(dir: File, order: List<String>): String {
        val results = readResults(dir)
        val themes = results.map { it.theme }.distinct().sorted()
        val byRow = results.groupBy { it.rowId }
        val rows = order.filter { it in byRow } + byRow.keys.filterNot { it in order }
        val lines = listOf("# Shared chat rows vs their goldens (ckmub.2.2)", "", INTRO, "") +
            header(themes) +
            rows.map { tableRow(it, byRow.getValue(it), themes) }
        val markdown = lines.joinToString("\n", postfix = "\n")
        dir.resolve(FILE_NAME).writeText(markdown)
        return markdown
    }

    private fun readResults(dir: File): List<ParityResult> =
        dir.listFiles { f -> f.name.startsWith("results-") && f.name.endsWith(".tsv") }
            .orEmpty()
            .flatMap { it.readLines().mapNotNull(ParityResult::parse) }

    private fun header(themes: List<String>): List<String> = listOf(
        "| Row type |" + themes.joinToString("") { " $it diff % | $it golden/shared dp |" } + " Notes |",
        "|---|" + themes.joinToString("") { "---:|---:|" } + "---|",
    )

    private fun tableRow(rowId: String, results: List<ParityResult>, themes: List<String>): String {
        val perTheme = results.associateBy { it.theme }
        val cells = themes.joinToString("") { theme -> cells(perTheme[theme]) }
        val notes = results.map { it.note }.filter { it.isNotBlank() }.distinct().joinToString("; ")
        return "| ${results.first().label} (`$rowId`) |$cells $notes |"
    }

    private fun cells(result: ParityResult?): String {
        val pct = result?.diffPercent?.let { String.format(Locale.US, "%.2f", it) } ?: NOT_AVAILABLE
        val heights = result?.heights() ?: NOT_AVAILABLE
        return " $pct | $heights |"
    }

    private fun ParityResult.heights(): String? {
        val golden = goldenHeightDp ?: return null
        val shared = sharedHeightDp ?: return null
        return "$golden/$shared"
    }
}
