package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.BaseRule
import io.gitlab.arturbosch.detekt.api.Finding
import io.gitlab.arturbosch.detekt.test.lint
import java.io.File

/** Lints [code] as if it lived at [relativePath] under [root], for rules that look at the file path. */
internal fun BaseRule.lintAt(root: File, relativePath: String, code: String): List<Finding> {
    val file = File(root, relativePath)
    file.parentFile.mkdirs()
    file.writeText(code)
    return lint(file.toPath())
}
