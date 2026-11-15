package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/** Paths that hold design values. Every design-system rule exempts them, so nothing that draws may live there. */
private val THEME_PATH_MARKERS = listOf("/ui/theme/", "/ui/tokens/")

private fun KtFile.isThemePath(): Boolean = isUiSource() && THEME_PATH_MARKERS.any { it in uiPath() }

private fun KtNamedFunction.hasAnnotation(name: String): Boolean =
    annotationEntries.any { it.shortName?.asString() == name }

/**
 * A `@Composable` component declared in a theme or token path.
 *
 * Theme paths are exempt from the design-system rules because they define the raw
 * values. A component parked there (a pill, a progress bar, a status glyph) is
 * therefore never checked, so it can hard-code what every other file must not.
 *
 * Value accessors stay: `@ReadOnlyComposable` functions and composables with an
 * expression body or a non-Unit return type read a value rather than emit UI.
 * Composables that install values for their content are allowed too: theme wrappers
 * named `...Theme` (`LettaTheme { content() }`) and providers that call
 * `CompositionLocalProvider` (`ProvideAccessibilityPolicy`, `TimelineZoomScope`).
 */
internal class ComponentInExemptPath(config: Config = Config.empty) : MobileRule(
    config,
    "ComponentInExemptPath",
    "Theme and token paths hold values; composable components belong in ui/components.",
) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.emitsUi() || !function.containingKtFile.isThemePath()) return
        report(
            function,
            "`${function.name}` is a composable component in a theme/token path, where no design-system " +
                "rule checks it. Move it to ui/components; theme paths hold values.",
        )
    }

    private fun KtNamedFunction.emitsUi(): Boolean =
        hasAnnotation("Composable") && returnsUnit() && !isValueOrThemeWrapper()

    private fun KtNamedFunction.returnsUnit(): Boolean =
        typeReference?.text == "Unit" || (typeReference == null && hasBlockBody())

    private fun KtNamedFunction.isValueOrThemeWrapper(): Boolean =
        isLocal || hasAnnotation("ReadOnlyComposable") || name.orEmpty().endsWith("Theme") || providesLocals()

    private fun KtNamedFunction.providesLocals(): Boolean =
        bodyExpression?.collectDescendantsOfType<KtCallExpression>()
            ?.any { it.calleeExpression?.text == "CompositionLocalProvider" } == true
}
