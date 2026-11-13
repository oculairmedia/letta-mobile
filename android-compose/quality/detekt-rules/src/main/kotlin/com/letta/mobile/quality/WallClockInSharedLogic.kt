package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Flags direct wall-clock reads (`System.currentTimeMillis()`, `System.nanoTime()`,
 * `Clock.System.now()`, `Instant.now()`, `LocalDateTime.now()` and friends, `Date()`) in production
 * code, so time-dependent logic takes an injected clock and tests can drive it deterministically.
 *
 * A read inside a parameter's default value is the injection point itself (`now: () -> Long =
 * { Clock.System.now().toEpochMilliseconds() }`) and is not reported. The rule is meant to be
 * scoped with detekt `includes` to packages that already inject their clock, as a ratchet.
 */
internal class WallClockInSharedLogic(config: Config = Config.empty) : MobileRule(
    config,
    "WallClockInSharedLogic",
    "Time-dependent shared logic must read an injected clock, not the system clock.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!isWallClockRead(expression)) return
        if (expression.getParentOfType<KtParameter>(strict = true) != null) return
        report(
            expression,
            "Do not read the system clock here: inject a kotlin.time.Clock (or a `now: () -> Long`) " +
                "through the constructor and call it, so tests can control time.",
        )
    }

    private fun isWallClockRead(call: KtCallExpression): Boolean {
        val hasArguments = call.valueArguments.isNotEmpty() || call.lambdaArguments.isNotEmpty()
        val name = call.calleeExpression?.text?.takeUnless { hasArguments }
        val receiver = (call.parent as? KtQualifiedExpression)
            ?.takeIf { it.selectorExpression == call }
            ?.receiverExpression?.text
            ?.removePrefix("java.lang.")?.removePrefix("kotlin.time.")?.removePrefix("kotlinx.datetime.")
            ?.substringAfterLast("java.time.")
        return when (receiver) {
            null -> name == "Date"
            "System" -> name == "currentTimeMillis" || name == "nanoTime"
            else -> name == "now" && receiver in NOW_RECEIVERS
        }
    }

    private companion object {
        val NOW_RECEIVERS = setOf(
            "Clock.System", "Instant", "LocalDateTime", "LocalDate", "LocalTime", "ZonedDateTime", "OffsetDateTime",
        )
    }
}
