package com.letta.mobile.quality.guardrails

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Flags a `snapshotFlow { ... }` lambda that reads a plain Kotlin `var`. `snapshotFlow` re-emits
 * only when its lambda reads Compose Snapshot state at collection time; a plain `var` holds its
 * value outside the snapshot system, so mutating it never invalidates the flow, the emitted value
 * stays stale forever, and dependent effects (auto-follow among them) silently stop updating
 * while their tests pass vacuously. State must be Snapshot-backed (`mutableStateOf` and friends,
 * a `State`-typed value, or a delegate such as `by rememberUpdatedState`) for the read inside the
 * lambda to be observable.
 *
 * Only reads whose binding this file proves are checked: a bare name (or `this.name`) is
 * resolved by walking Kotlin's own scope chain to its innermost enclosing declaration, and reads
 * through any other receiver or names declared in other files are left alone. Whatever remains
 * is a `var` declared right here whose type and initializer cannot hold Snapshot state.
 */
internal class NonObservableStateInSnapshotFlow(config: Config = Config.empty) : ProductionSourceRule(
    config,
    "NonObservableStateInSnapshotFlow",
    "A snapshotFlow lambda must read Compose snapshot state; a plain var never re-emits it.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != SNAPSHOT_FLOW) return
        val body = expression.valueArguments
            .asSequence()
            .mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }
            .firstOrNull()
            ?.bodyExpression
            ?: return
        body.collectDescendantsOfType<KtNameReferenceExpression>()
            .filter { it.readsNonObservableVar() }
            .forEach { reference ->
                report(
                    reference,
                    "'${reference.getReferencedName()}' is a plain var, so snapshotFlow cannot observe its " +
                        "changes; back it with mutableStateOf (or another Snapshot-backed value) so the " +
                        "flow re-emits when the state mutates.",
                )
            }
    }

    /**
     * A plain `var` read, per the binding this file proves: not through a callable reference or
     * a foreign receiver, and resolved to a `var` without delegate or Snapshot state.
     */
    private fun KtNameReferenceExpression.readsNonObservableVar(): Boolean =
        parent !is KtCallableReferenceExpression &&
            !isSelectorOfForeignReceiver() &&
            resolveVisibleDeclaration()?.isPlainVar() == true

    /** A read through another receiver may belong to another file's declaration; leave it alone. */
    private fun KtNameReferenceExpression.isSelectorOfForeignReceiver(): Boolean {
        val qualified = parent as? KtQualifiedExpression ?: return false
        return qualified.selectorExpression == this && qualified.receiverExpression.text != "this"
    }

    /**
     * Innermost scope wins, exactly as Kotlin binds simple names: local `var`s (only when declared
     * before the reference), lambda and function parameters, loop variables, class, constructor
     * and file properties. Declarations of other files are deliberately unreachable.
     */
    private fun KtNameReferenceExpression.resolveVisibleDeclaration(): KtDeclaration? {
        val name = getReferencedName()
        var scope: PsiElement? = parent
        while (scope != null) {
            val declaration = when (scope) {
                is KtBlockExpression -> scope.statements.filterIsInstance<KtProperty>()
                    .firstOrNull { it.name == name && it.textOffset < textOffset }
                is KtFunctionLiteral -> scope.valueParameters.firstOrNull { it.name == name }
                is KtNamedFunction -> scope.valueParameters.firstOrNull { it.name == name }
                is KtForExpression -> scope.loopParameter?.takeIf { it.name == name }
                is KtClassBody -> scope.declarations.filterIsInstance<KtProperty>()
                    .firstOrNull { it.name == name }
                is KtClass -> scope.primaryConstructor?.valueParameters
                    ?.firstOrNull { it.hasValOrVar() && it.name == name }
                is KtFile -> scope.declarations.filterIsInstance<KtProperty>()
                    .firstOrNull { it.name == name }
                else -> null
            }
            if (declaration != null) return declaration
            scope = scope.parent
        }
        return null
    }

    /** A `var` without a delegate and without Snapshot state in its type or initializer. */
    private fun KtDeclaration.isPlainVar(): Boolean = when (this) {
        is KtProperty ->
            isVar &&
                !hasDelegate() &&
                receiverTypeReference == null &&
                !holdsSnapshotState(typeReference?.text, initializer?.text)
        is KtParameter ->
            hasValOrVar() && isMutable && !holdsSnapshotState(typeReference?.text, null)
        else -> false
    }

    /** Snapshot state silences the check: a `State`-typed value or a snapshot-state initializer. */
    private fun holdsSnapshotState(type: String?, initializer: String?): Boolean =
        "State" in type.orEmpty() || "State" in initializer.orEmpty()

    private companion object {
        const val SNAPSHOT_FLOW = "snapshotFlow"
    }
}
