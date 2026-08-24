package com.letta.mobile.feature.chat.screen.messagelist

internal data class ChatVisibleItemBounds(
    val key: Any,
    val topPx: Int,
    val bottomPx: Int,
)

internal data class ChatItemPinchOwner(
    val key: Any,
    val outerHeightPx: Int,
)

internal class ChatItemPinchState {
    var owner: ChatItemPinchOwner? = null
        private set

    fun begin(centroidYPx: Float, visibleItems: List<ChatVisibleItemBounds>): ChatItemPinchOwner? {
        if (owner != null) return owner
        owner = visibleItems
            .firstOrNull { centroidYPx >= it.topPx && centroidYPx < it.bottomPx }
            ?.let { ChatItemPinchOwner(key = it.key, outerHeightPx = it.bottomPx - it.topPx) }
        return owner
    }

    fun reconcile(availableKeys: Collection<Any>): Boolean {
        val activeOwner = owner ?: return false
        if (activeOwner.key in availableKeys) return true
        owner = null
        return false
    }

    fun finish() {
        owner = null
    }
}

internal fun chatRenderItemSeesPinchPreview(ownerKey: Any?, itemKey: Any): Boolean =
    ownerKey != null && ownerKey == itemKey

internal fun boundedOuterHeightPx(owner: ChatItemPinchOwner?, itemKey: Any): Int? =
    owner?.takeIf { it.key == itemKey }?.outerHeightPx

internal fun finishLocalPinch(
    state: ChatItemPinchState,
    cancelPreview: () -> Unit,
    releaseAnimationSuppression: () -> Unit,
) {
    cancelPreview()
    state.finish()
    releaseAnimationSuppression()
}
