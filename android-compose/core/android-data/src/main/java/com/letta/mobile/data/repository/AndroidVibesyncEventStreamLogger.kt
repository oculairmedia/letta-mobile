package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.repository.api.VibesyncEventStreamLogger

class AndroidVibesyncEventStreamLogger(
    private val tag: String = TAG,
) : VibesyncEventStreamLogger {
    override fun info(message: String) {
        Log.i(tag, message)
    }

    override fun info(message: String, error: Throwable) {
        Log.i(tag, message, error)
    }

    private companion object {
        const val TAG = "VibesyncEvents"
    }
}
