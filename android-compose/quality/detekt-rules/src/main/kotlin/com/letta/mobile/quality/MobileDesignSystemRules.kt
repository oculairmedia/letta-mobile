package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Design-system guardrails for Compose source.
 *
 * These exist because desktop UI accumulated ~2600 hand-picked dimension
 * literals across ~230 files. With no shared token set, nothing forces two
 * controls on the same row to agree, so scale, spacing and contrast drift per
 * file and the fix for one screen never reaches the next.
 *
 * `scripts/ui-design-lint.py` applies the same three rules without Gradle, for
 * working through the backlog at speed. These are the CI copy: slower, but
 * they run on every PR, so cleaned surfaces stay clean.
 */

/** Source roots that hold Compose UI. Nothing else is checked. */
private val UI_PATH_MARKERS = listOf(
    "/sharedUI/src/",
    "/desktop/src/main/",
    "/app/src/main/",
    "/feature-chat/src/main/",
    "/feature-editagent/src/main/",
    "/designsystem/src/main/",
)

/**
 * Where the raw values are DEFINED. A token file is the one place a literal is
 * the point rather than a leak.
 */
private val TOKEN_FILE_MARKERS = listOf(
    "/ui/theme/",
    "/ui/tokens/",
    "LettaDimens.kt",
    "LettaTokens.kt",
    "Dimens.kt",
    "Type.kt",
    "Theme.kt",
    "Color.kt",
    "Shape.kt",
)

/** Spikes, benches and previews are not product surfaces. */
private val EXEMPT_MARKERS = listOf("Spike", "Bench", "Preview")

private fun KtFile.uiPath(): String = virtualFilePath.replace('\\', '/')

private fun KtFile.isUiSource(): Boolean {
    val path = uiPath()
    if (EXEMPT_MARKERS.any { it in path }) return false
    if ("/build/" in path || "/generated/" in path) return false
    if (path.substringAfter("/src/", "").substringBefore('/').contains("test", ignoreCase = true)) {
        return false
    }
    return UI_PATH_MARKERS.any { it in path }
}

private fun KtFile.isTokenFile(): Boolean = TOKEN_FILE_MARKERS.any { it in uiPath() }

/**
 * `12.dp` / `14.sp` written at a call site instead of read from a token.
 *
 * 0.dp and 1.dp are exempt: they mean "no inset" and "hairline", which are
 * structural facts rather than points on a scale, and tokenising them would
 * bury the findings that matter.
 */
internal class RawDimensionLiteral(config: Config = Config.empty) : MobileRule(
    config,
    "RawDimensionLiteral",
    "Compose dimensions must come from design tokens so surfaces stay on one scale.",
) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val file = expression.containingKtFile
        if (!file.isUiSource() || file.isTokenFile()) return

        val unit = expression.selectorExpression?.text ?: return
        if (unit != "dp" && unit != "sp") return
        val value = expression.receiverExpression.text
        if (!value.matches(NUMERIC)) return
        if (unit == "dp" && value in ALLOWED_DP) return

        report(
            expression,
            "`$value.$unit` is a raw dimension. Read it from a design token instead, so " +
                "controls on the same surface share one scale.",
        )
    }

    private companion object {
        val NUMERIC = Regex("""\d+(\.\d+)?""")
        val ALLOWED_DP = setOf("0", "1")
    }
}

/** `Color(0xFF2A2A2A)` at a call site, which cannot follow the light/dark theme. */
internal class HardcodedComposeColor(config: Config = Config.empty) : MobileRule(
    config,
    "HardcodedComposeColor",
    "Compose colours must come from MaterialTheme roles so themes stay correct.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isUiSource() || file.isTokenFile()) return
        if (expression.calleeExpression?.text != "Color") return

        val arguments = expression.valueArguments
        if (arguments.size != 1) return
        val literal = arguments.single().getArgumentExpression()?.text ?: return
        if (!literal.matches(HEX)) return

        report(
            expression,
            "`Color($literal)` bypasses the theme. Use a MaterialTheme colour role (or add one) " +
                "so light and dark both stay correct.",
        )
    }

    private companion object {
        val HEX = Regex("""0[xX][0-9a-fA-F]{6,8}""")
    }
}

/**
 * `.copy(alpha = 0.5f)` on a colour, below the legibility floor.
 *
 * This is the barely-visible-control bug: `onSurfaceVariant` is already the
 * muted role, and halving it again puts a disabled button under the contrast it
 * needs to still read as a control on a dark theme.
 */
internal class LowContentAlpha(config: Config = Config.empty) : MobileRule(
    config,
    "LowContentAlpha",
    "Content alpha below the legibility floor makes controls unreadable on dark themes.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isUiSource()) return
        if (expression.calleeExpression?.text != "copy") return

        val alpha = expression.valueArguments.firstOrNull { it.alphaValue() != null } ?: return
        val value = alpha.alphaValue() ?: return
        if (value >= FLOOR) return

        report(
            expression,
            "alpha $value is below the $FLOOR legibility floor. Muted content roles are already " +
                "dimmed; dimming them again makes the control unreadable on dark themes.",
        )
    }

    private fun KtValueArgument.alphaValue(): Double? {
        if (getArgumentName()?.asName?.asString() != "alpha") return null
        val text = getArgumentExpression()?.text?.removeSuffix("f") ?: return null
        return text.toDoubleOrNull()
    }

    private companion object {
        const val FLOOR = 0.6
    }
}

class MobileDesignSystemProvider : RuleSetProvider {
    override val ruleSetId: String = "letta-mobile-design-system"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            RawDimensionLiteral(config),
            HardcodedComposeColor(config),
            LowContentAlpha(config),
        ),
    )
}
