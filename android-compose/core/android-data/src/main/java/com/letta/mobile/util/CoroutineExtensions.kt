package com.letta.mobile.util

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun ViewModel.safeLaunch(
    tag: String = this::class.java.simpleName,
    onError: ((Exception) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit,
) = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(tag, "Coroutine failed", e)
        onError?.invoke(e)
    }
}
