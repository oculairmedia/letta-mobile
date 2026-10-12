package com.letta.mobile.feature.chat

import com.letta.mobile.ui.chat.session.ChatActions
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The shared page's intents, all ignored: screenshot tests only draw. A proxy rather than a
 * hand-written override per intent, so a new [ChatActions] member needs no edit here.
 */
internal val NoOpChatActions: ChatActions = Proxy.newProxyInstance(
    ChatActions::class.java.classLoader,
    arrayOf(ChatActions::class.java),
    NoOpChatActionsHandler,
) as ChatActions

/** Every [ChatActions] member returns Unit; Object's own members keep an object's identity semantics. */
private object NoOpChatActionsHandler : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "NoOpChatActions"
            else -> Unit
        }
    }
}
