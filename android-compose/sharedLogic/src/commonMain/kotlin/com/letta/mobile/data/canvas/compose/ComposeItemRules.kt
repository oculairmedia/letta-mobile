package com.letta.mobile.data.canvas.compose

/**
 * Every per-item rule of a decoded [request] that a JSON schema cannot say, in request order: a
 * kind newer than the version the request names, a TABLE row whose cell count is not the column
 * count, and a bar CHART value under zero (bars grow from zero). Each problem carries the JSON
 * pointer of the value to change, like every other refusal.
 */
internal fun itemRuleProblems(request: ComposeRequest): List<ComposeProblem> = buildList {
    request.items.forEachIndexed { i, item ->
        val path = "/items/$i"
        addAll(itemProblems(item, path, request.version))
        (item as? ComposeItem.Group)?.children?.forEachIndexed { c, child ->
            addAll(itemProblems(child, "$path/children/$c", request.version))
        }
    }
}

private fun itemProblems(item: ComposeItem, path: String, version: Int?): List<ComposeProblem> =
    listOfNotNull(versionProblem(item, path, version)) + when (item) {
        is ComposeItem.Table -> rowProblems(item, path)
        is ComposeItem.Chart -> barProblems(item, path)
        else -> emptyList()
    }

private fun versionProblem(item: ComposeItem, path: String, version: Int?): ComposeProblem? {
    if (version == null || item.kind.since <= version) return null
    return ComposeProblem(
        "$path/kind", ComposeProblemCode.BAD_VALUE,
        "${item.kind.name} needs version ${item.kind.since}; send \"version\": ${CanvasComposeContract.VERSION} or leave version out",
    )
}

private fun rowProblems(table: ComposeItem.Table, path: String): List<ComposeProblem> =
    table.rows.mapIndexedNotNull { r, row ->
        ComposeProblem(
            "$path/rows/$r", ComposeProblemCode.BAD_VALUE,
            "row $r has ${row.size} cells; the table has ${table.columns.size} columns, so give each row one cell per column",
        ).takeIf { row.size != table.columns.size }
    }

private fun barProblems(chart: ComposeItem.Chart, path: String): List<ComposeProblem> {
    if (chart.style != ComposeChartStyle.BAR) return emptyList()
    return chart.points.mapIndexedNotNull { p, point ->
        ComposeProblem(
            "$path/points/$p/value", ComposeProblemCode.BAD_VALUE,
            "${point.value} is under 0; bars grow from 0, so use a line chart for negative values",
        ).takeIf { point.value < 0.0 }
    }
}
