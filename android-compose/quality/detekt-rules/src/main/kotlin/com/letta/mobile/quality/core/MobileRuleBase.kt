package com.letta.mobile.quality.core

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

internal fun KtFile.normalizedPath(): String = virtualFilePath.replace('\\', '/')
internal fun KtFile.isTestSource(): Boolean = normalizedPath().contains("/src/") &&
    normalizedPath().substringAfter("/src/").substringBefore('/').contains("test", ignoreCase = true)
internal fun KtFile.isGenerated(): Boolean = normalizedPath().let {
    "/generated/" in it || "/build/" in it || "/ksp/" in it
}

internal abstract class MobileRule(config: Config, name: String, description: String) : Rule(config) {
    final override val issue = Issue(name, Severity.Defect, description, Debt.TWENTY_MINS)

    protected fun report(element: KtElement, message: String) {
        report(CodeSmell(issue, Entity.from(element), message))
    }
}
