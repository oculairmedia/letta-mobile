package com.letta.mobile.avatar.rive

import app.rive.ViewModelInstance

/**
 * Binds the shared [RiveInputSink] to a Rive view model instance on Android.
 *
 * This is the entire platform-specific part of the renderer. Everything that decides *what* to
 * write - the state mapping, the clamps, which commands a flat rig drops - is common code in
 * [RiveAvatarRuntime], and this class only carries the write across.
 *
 * Property paths are matched by name inside the `.riv`. A path the asset does not declare writes
 * nothing and reports nothing, which is why the names live in [RiveAvatarContract] next to the
 * authoring source rather than being spelled at call sites.
 */
class ViewModelInstanceInputSink(
    private val instance: ViewModelInstance,
) : RiveInputSink {

    override fun setNumber(input: String, value: Float) {
        instance.setNumber(input, value)
    }

    override fun setBoolean(input: String, value: Boolean) {
        instance.setBoolean(input, value)
    }

    override fun setEnum(input: String, key: String) {
        instance.setEnum(input, key)
    }

    override fun setColor(input: String, argb: Int) {
        instance.setColor(input, argb)
    }

    override fun fire(input: String) {
        instance.fireTrigger(input)
    }
}
