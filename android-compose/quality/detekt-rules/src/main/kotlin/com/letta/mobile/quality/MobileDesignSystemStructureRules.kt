package com.letta.mobile.quality

import com.letta.mobile.quality.core.MobileRule
import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents

/*
 * Rules that ban hand-built copies of shared UI elements.
 *
 * Each one finds a raw part (a corner radius, a font weight, a chevron, a status
 * icon, a click target) used directly at a call site. Every such use is a place
 * where one screen drifts from the next. The shared implementations in
 * `ui/components/` and the definitions in token and theme files are where these
 * parts belong, so those paths are exempt.
 *
 * All checks are syntactic: guardrailDetekt runs without type resolution.
 */

private fun KtFile.isStyledUiSource(): Boolean = isUiSource() && !isTokenFile()

/** Receivers that name an icon: the app registry, Lucide, or a Material icon style. */
private const val ICON_RECEIVER =
    """(LettaIcons|Lucide|Icons(\.AutoMirrored)?\.(Default|Filled|Outlined|Rounded|Sharp|TwoTone))"""

private val DISCLOSURE_ICON = Regex(
    "$ICON_RECEIVER\\.(Chevrons?\\w*|ExpandMore|ExpandLess|ExpandCircleDown|ExpandCircleUp|" +
        "ArrowDrop(Down|Up)|KeyboardArrow(Down|Up|Left|Right)|KeyboardDoubleArrow\\w+|" +
        "NavigateNext|NavigateBefore|UnfoldMore|UnfoldLess)",
)

private val STATUS_ICON = Regex(
    "$ICON_RECEIVER\\.(Check|CheckCircle|CheckCircleOutline|CircleCheck|CircleCheckBig|SquareCheck|" +
        "Error|ErrorOutline|Warning|WarningAmber|Info|InfoOutline|CircleAlert|TriangleAlert|" +
        "OctagonAlert|AlertCircle|AlertTriangle|CircleX|XCircle|Cancel|" +
        "Circle|CircleDot|CircleDashed|RadioButtonUnchecked|RadioButtonChecked)",
)

private fun KtCallExpression.calleeName(): String? = calleeExpression?.text

private fun KtValueArgument.name(): String? = getArgumentName()?.asName?.asString()

/** Source text without whitespace, so `LettaIcons` + newline + `.ChevronDown` reads as one reference. */
private fun KtExpression.compactText(): String = text.filterNot(Char::isWhitespace)

/**
 * `RoundedCornerShape(8.dp)` at a call site. Corner radii are part of the shape
 * scale, so two cards on one screen should not pick their own.
 */
internal class RawShapeLiteral(config: Config = Config.empty) : MobileRule(
    config,
    "RawShapeLiteral",
    "Compose shapes must come from the shape scale so surfaces share one set of corners.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeName()
        if (callee !in SHAPE_CONSTRUCTORS || !expression.containingKtFile.isStyledUiSource()) return
        report(
            expression,
            "`$callee(...)` builds a shape at the call site. Use MaterialTheme.shapes or a named shape " +
                "token (CircleShape is fine for true circles).",
        )
    }

    private companion object {
        val SHAPE_CONSTRUCTORS = setOf(
            "RoundedCornerShape",
            "CutCornerShape",
            "AbsoluteRoundedCornerShape",
            "AbsoluteCutCornerShape",
        )
    }
}

/**
 * Text metrics set piecemeal at a call site: `fontWeight = FontWeight.SemiBold`,
 * `fontSize = 13.sp`, or `MaterialTheme.typography.bodySmall.copy(...)`.
 *
 * A named argument whose value is plain data (a parameter or a lowercase property
 * chain such as `fontWeight = weight`) is forwarding, not choosing, and passes:
 * the choice is made, and checked, where the value comes from. That is why any
 * `FontWeight.X` / `FontFamily.X` reference is also reported where it appears
 * (for example `val weight = FontWeight.Bold`), once per choice.
 */
internal class TypographyOverride(config: Config = Config.empty) : MobileRule(
    config,
    "TypographyOverride",
    "Text metrics must come from a named text style so the type scale stays consistent.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.containingKtFile.isStyledUiSource()) return
        if (expression.isTypographyCopy()) {
            report(
                expression,
                "`${expression.receiverText()}.copy(...)` derives a one-off text style. Use a named " +
                    "text style token (add one to the type tokens if none fits).",
            )
            return
        }
        expression.valueArguments
            .filter { it.name() in TEXT_METRICS && it.choosesConstant() }
            .forEach { argument ->
                report(
                    argument,
                    "`${argument.name()} = ...` overrides text metrics at the call site. Use a named " +
                        "text style token (add one to the type tokens if none fits).",
                )
            }
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (!FONT_CONSTANT.matches(expression.compactText())) return
        if (!expression.containingKtFile.isStyledUiSource() || expression.isInsideReportedChoice()) return
        report(
            expression,
            "`${expression.compactText()}` picks a font at the call site. Use a named text style token " +
                "(add one to the type tokens if none fits).",
        )
    }

    /** Already reported through its metric argument or typography copy. */
    private fun KtDotQualifiedExpression.isInsideReportedChoice(): Boolean = parents.any { parent ->
        (parent is KtValueArgument && parent.name() in TEXT_METRICS) ||
            (parent is KtCallExpression && parent.isTypographyCopy())
    }

    private fun KtCallExpression.receiverText(): String? =
        (parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == this }?.receiverExpression?.text

    private fun KtCallExpression.isTypographyCopy(): Boolean =
        calleeName() == "copy" && receiverText()?.let(TYPOGRAPHY_STYLE::matches) == true

    private fun KtValueArgument.choosesConstant(): Boolean {
        val value = getArgumentExpression()?.text ?: return false
        return CONSTANT_SOURCE.containsMatchIn(value)
    }

    private companion object {
        /** `FontWeight.Bold`, `FontFamily.Monospace`: a font choice wherever it is written. */
        val FONT_CONSTANT = Regex("""(FontWeight|FontFamily)\.[A-Z]\w*""")

        val TEXT_METRICS = setOf("fontSize", "fontWeight", "fontFamily", "letterSpacing", "lineHeight")

        /** `MaterialTheme.typography.x`, `typography.x`, or a `...Type.x` token. */
        val TYPOGRAPHY_STYLE = Regex("""(.*\.)?(typography|([A-Z]\w*)?Type)\.\w+""")

        /** A number, or an identifier starting with a capital: a literal, constant, or object. */
        val CONSTANT_SOURCE = Regex("""(^|[^\w.])([A-Z]|\d)""")
    }
}

/**
 * A chevron, drop arrow or expand icon referenced directly. Disclosure state is
 * drawn by DisclosureChevron (rotation, tint, reduced motion); a hand-picked
 * arrow icon drifts from it.
 */
internal class RawDisclosureIcon(config: Config = Config.empty) : MobileRule(
    config,
    "RawDisclosureIcon",
    "Disclosure arrows must come from DisclosureChevron or a shared pager component.",
) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val reference = expression.compactText()
        if (!DISCLOSURE_ICON.matches(reference) || !expression.containingKtFile.isFeatureUiSource()) return
        report(
            expression,
            "`$reference` hand-draws a disclosure arrow. Use DisclosureChevron for expand and " +
                "collapse, or a shared pager component for previous/next.",
        )
    }
}

/**
 * DisclosureChevron placed by hand in a feature row. The chevron belongs to the
 * shared disclosure line, which also owns the click target, role and spacing.
 */
internal class DisclosureOutsideSharedRow(config: Config = Config.empty) : MobileRule(
    config,
    "DisclosureOutsideSharedRow",
    "DisclosureChevron must be placed by the shared disclosure line components.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeName() != "DisclosureChevron") return
        if (!expression.containingKtFile.isFeatureUiSource()) return
        report(
            expression,
            "DisclosureChevron is placed by hand here. Use the shared disclosure line components in " +
                "ui/components, which own the chevron, click target and role together.",
        )
    }
}

/**
 * `Icon(LettaIcons.CheckCircle, ...)` and friends: a status drawn by hand. Each
 * copy picks its own size, tint and content description for the same state.
 *
 * An argument that is a plain name is followed to a `val`/`var` of that name in the
 * same function, so `val icon = when (state) { ... -> LettaIcons.Error }` and then
 * `Icon(icon, ...)` is caught too.
 */
internal class RawStatusGlyph(config: Config = Config.empty) : MobileRule(
    config,
    "RawStatusGlyph",
    "Status icons must be drawn by the shared status glyph so states look the same everywhere.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeName() !in ICON_CALLS || !expression.containingKtFile.isFeatureUiSource()) return
        val glyph = expression.valueArguments
            .mapNotNull(KtValueArgument::getArgumentExpression)
            .firstNotNullOfOrNull { it.statusIconReference() }
            ?: return
        report(
            expression,
            "`$glyph` is drawn as a status by hand. Use the shared status glyph in ui/components " +
                "(add the state there if it is missing).",
        )
    }

    private fun KtExpression.statusIconReference(): String? {
        val candidates = listOfNotNull(this as? KtDotQualifiedExpression) +
            collectDescendantsOfType<KtDotQualifiedExpression>()
        return candidates.map { it.compactText() }.firstOrNull(STATUS_ICON::matches)
            ?: (this as? KtNameReferenceExpression)?.localInitializer()?.statusIconReference()
    }

    /** The initializer of a local `val`/`var` with this name in the enclosing function, if any. */
    private fun KtNameReferenceExpression.localInitializer(): KtExpression? {
        val function = getParentOfType<KtDeclarationWithBody>(strict = true) ?: return null
        return function.collectDescendantsOfType<KtProperty> { it.isLocal && it.name == getReferencedName() }
            .firstNotNullOfOrNull { it.initializer }
    }

    private companion object {
        val ICON_CALLS = setOf("Icon", "Image")
    }
}

/**
 * `.clickable { }`, `.combinedClickable { }`, `.selectable(...)` or `.toggleable(...)`
 * without a semantic role. Screen readers then announce a plain element, and each
 * copy picks its own indication and padding.
 */
internal class BareClickable(config: Config = Config.empty) : MobileRule(
    config,
    "BareClickable",
    "Click targets must declare a role or use a shared toggle/button modifier.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.isBareClickable() || !expression.containingKtFile.isFeatureUiSource()) return
        report(
            expression,
            "`${expression.calleeName()}` has no role. Pass `role = Role.Button` (or the matching role), " +
                "or use a shared toggle/button modifier from ui/components.",
        )
    }

    private fun KtCallExpression.isBareClickable(): Boolean = calleeName() in CLICK_MODIFIERS && !declaresRole()

    /**
     * A named `role =` other than `null`, or a positional value that names a role (`Role.Button`,
     * `buttonRole`) in the role slot. All four modifiers put it at index 2 in the short overload
     * (`clickable(enabled, onClickLabel, role, onClick)`, `selectable(selected, enabled, role, onClick)`)
     * and at index 4 in the interactionSource overload
     * (`clickable(interactionSource, indication, enabled, onClickLabel, role, onClick)`).
     *
     * Without type resolution a positional value cannot be told apart from the `enabled` flag of the
     * second overload (`clickable(source, null, enabled)`), so it only counts when its text names a
     * role. Anything else is reported; writing `role = ...` as a named argument resolves it.
     */
    private fun KtCallExpression.declaresRole(): Boolean {
        val named = valueArguments.firstOrNull { it.name() == "role" }
        if (named != null) return named.getArgumentExpression()?.text != "null"
        val positional = valueArguments
            .filter { it.name() == null && it !is KtLambdaArgument }
            .map { it.getArgumentExpression()?.text.orEmpty() }
        val slot = if (positional.size > INDICATION_ROLE_SLOT) INDICATION_ROLE_SLOT else ENABLED_ROLE_SLOT
        return positional.getOrNull(slot)?.contains("role", ignoreCase = true) == true
    }

    private companion object {
        const val ENABLED_ROLE_SLOT = 2
        const val INDICATION_ROLE_SLOT = 4
        val CLICK_MODIFIERS = setOf("clickable", "combinedClickable", "selectable", "toggleable")
    }
}
