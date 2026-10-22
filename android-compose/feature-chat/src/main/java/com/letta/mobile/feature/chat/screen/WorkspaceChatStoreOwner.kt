package com.letta.mobile.feature.chat.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.runtime.RememberObserver
import androidx.hilt.lifecycle.viewmodel.HiltViewModelFactory
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.navigation.NavBackStackEntry

/**
 * Where one chat's view model lives inside the shell workspace (letta-mobile-ckmub.2): the workspace
 * route's store holds a store per chat ([SharedNavDrawerViewModel.chatStore]), so a chat keeps its view
 * model across a rotation and drops it once the page shows another chat or leaves. The view model reads
 * its route from [args], as it did from a navigation route.
 */
internal class WorkspaceChatStoreOwner(
    private val key: String,
    private val drawer: SharedNavDrawerViewModel,
    private val parent: NavBackStackEntry,
    private val context: Context,
    private val args: Bundle,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory, RememberObserver {
    override val viewModelStore: ViewModelStore = drawer.chatStore(key)

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        HiltViewModelFactory(context, parent.defaultViewModelProviderFactory)

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(parent.defaultViewModelCreationExtras).apply {
            set(DEFAULT_ARGS_KEY, args)
            set(VIEW_MODEL_STORE_OWNER_KEY, this@WorkspaceChatStoreOwner)
        }

    override fun onRemembered() = Unit

    override fun onForgotten() {
        // A rotation recreates the page over the same store; anything else leaves the chat.
        if (context.activity()?.isChangingConfigurations != true) drawer.releaseChat(key)
    }

    override fun onAbandoned() = onForgotten()

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
