package com.letta.mobile.ui.a11y

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/** Clickable nodes that have no text, content description, or click label and are not hidden. */
fun unlabeledClickables(nodes: Iterable<SemanticsNode>): List<SemanticsNode> =
    nodes.filter { it.isUnlabeledClickable() }

private fun SemanticsNode.isUnlabeledClickable(): Boolean {
    if (config.getOrNull(SemanticsActions.OnClick) == null) return false
    if (config.contains(SemanticsProperties.InvisibleToUser)) return false
    return !hasAccessibleName()
}

private fun SemanticsNode.hasAccessibleName(): Boolean {
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
    val clickLabel = config.getOrNull(SemanticsActions.OnClick)?.label.orEmpty()
    return text.isNotBlank() || description.isNotBlank() || clickLabel.isNotBlank()
}
