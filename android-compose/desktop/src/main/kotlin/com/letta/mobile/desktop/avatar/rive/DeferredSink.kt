package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.avatar.rive.RiveInputSink

/**
 * An input sink that records writes until a scene exists, then replays them in order and forwards
 * everything after. Lets the runtime and director be built - and written to - before the native
 * scene has been created.
 */
internal class DeferredSink : RiveInputSink {
    private var target: RiveInputSink? = null
    private val pending = ArrayList<(RiveInputSink) -> Unit>()

    fun attach(sink: RiveInputSink) {
        synchronized(pending) {
            pending.forEach { it(sink) }
            pending.clear()
            target = sink
        }
    }

    private fun write(op: (RiveInputSink) -> Unit) {
        synchronized(pending) {
            val t = target
            if (t != null) op(t) else pending += op
        }
    }

    override fun setNumber(input: String, value: Float) = write { it.setNumber(input, value) }
    override fun setBoolean(input: String, value: Boolean) = write { it.setBoolean(input, value) }
    override fun setEnum(input: String, key: String) = write { it.setEnum(input, key) }
    override fun setColor(input: String, argb: Int) = write { it.setColor(input, argb) }
    override fun fire(input: String) = write { it.fire(input) }
}
