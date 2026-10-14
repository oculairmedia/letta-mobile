package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * Flags a kotlinx `Json { ... }` / `Json(from) { ... }` configuration built outside the canonical
 * `LettaJson.kt` (letta-mobile-by4vm.20), so new code is pointed at `LettaJson.wire`,
 * `LettaJson.adminRpc` or `LettaJson.persisted` as soon as it is written.
 *
 * guardrailDetekt has no baseline and runs on every changed file, so files already frozen in
 * `android-compose/architecture-tests/json-instances-baseline.txt` are skipped; otherwise any PR
 * touching one of them would fail on debt it did not add. That file is the single list of known
 * debt, shared with `JsonInstanceRatchetTest`.
 */
internal class NoAdHocJsonInstance(
    config: Config = Config.empty,
    private val isBaselined: (KtFile) -> Boolean = JsonInstanceBaseline::contains,
) : MobileRule(
    config,
    "NoAdHocJsonInstance",
    "Use a canonical LettaJson instance instead of building a new kotlinx Json configuration.",
) {
    private var skipFile = false

    override fun visitKtFile(file: KtFile) {
        skipFile = file.isTestSource() || file.isGenerated() ||
            file.normalizedPath().endsWith(CANONICAL_SUFFIX) || isBaselined(file)
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (skipFile || expression.calleeExpression?.text != "Json") return
        if (expression.lambdaArguments.isEmpty()) return
        report(
            expression,
            "Use LettaJson.wire (App Server/Iroh frames), LettaJson.adminRpc (Letta REST over admin_rpc) " +
                "or LettaJson.persisted (local stores) instead of building a new Json configuration " +
                "(letta-mobile-by4vm.20).",
        )
    }

    private companion object {
        const val CANONICAL_SUFFIX =
            "/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/serialization/LettaJson.kt"
    }
}

/** Reads `json-instances-baseline.txt` relative to the `android-compose/` directory holding the file. */
internal object JsonInstanceBaseline {
    private const val MARKER = "/android-compose/"
    private const val BASELINE = "android-compose/architecture-tests/json-instances-baseline.txt"

    fun contains(file: KtFile): Boolean = contains(file.normalizedPath())

    fun contains(path: String): Boolean {
        val index = path.lastIndexOf(MARKER)
        val baseline = File(path.substring(0, index.coerceAtLeast(0)), BASELINE).takeIf { index >= 0 && it.isFile }
        val relative = path.substring(index + 1)
        return baseline?.useLines { lines -> lines.map(String::trim).any { it == relative } } ?: false
    }
}
