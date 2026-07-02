package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HeadlessAvatarRuntimeTest {
    private val model = AvatarModel(
        id = "avatar-1",
        displayName = "Test Avatar",
        uri = "file:///avatars/test.vrm",
        format = AvatarFormat.VRM_1,
    )

    @Test
    fun loadTransitionsIdleToReadyWithHumanoidCapabilities() = runTest {
        val runtime = HeadlessAvatarRuntime()
        assertEquals(AvatarRuntimeState.Idle, runtime.state.value)

        runtime.load(model)

        val ready = assertIs<AvatarRuntimeState.Ready>(runtime.state.value)
        assertEquals(model, ready.model)
        assertTrue(ready.capabilities.supportsHumanoid)
    }

    @Test
    fun nonHumanoidFormatReportsNoHumanoidSupport() = runTest {
        val runtime = HeadlessAvatarRuntime()
        runtime.load(model.copy(format = AvatarFormat.GLB))

        val ready = assertIs<AvatarRuntimeState.Ready>(runtime.state.value)
        assertEquals(false, ready.capabilities.supportsHumanoid)
    }

    @Test
    fun commandsAreRecordedAndClampedWhileReady() = runTest {
        val runtime = HeadlessAvatarRuntime()
        runtime.load(model)

        runtime.setExpression(AvatarExpression.Happy, 1.7f)
        runtime.setViseme(AvatarViseme.A, -0.3f)
        runtime.setMouthOpen(0.5f)
        runtime.setLookTarget(AvatarLookTarget.Screen(0.5f, 0.25f))
        runtime.setAccessoryEnabled("glasses", enabled = false)

        assertEquals(1f, runtime.expressionWeights["happy"])
        assertEquals(0f, runtime.visemeWeights["aa"])
        assertEquals(0.5f, runtime.mouthOpen)
        assertEquals(AvatarLookTarget.Screen(0.5f, 0.25f), runtime.lookTarget)
        assertEquals(setOf("glasses"), runtime.disabledAccessoryIds)
    }

    @Test
    fun commandsBeforeLoadAreDropped() {
        val runtime = HeadlessAvatarRuntime()

        runtime.setExpression(AvatarExpression.Happy)
        runtime.setMouthOpen(1f)

        assertTrue(runtime.expressionWeights.isEmpty())
        assertEquals(0f, runtime.mouthOpen)
    }

    @Test
    fun unloadResetsCommandStateAndReturnsToIdle() = runTest {
        val runtime = HeadlessAvatarRuntime()
        runtime.load(model)
        runtime.setExpression(AvatarExpression.Happy)
        runtime.setLookTarget(AvatarLookTarget.World(0f, 1f, 2f))

        runtime.unload()

        assertEquals(AvatarRuntimeState.Idle, runtime.state.value)
        assertTrue(runtime.expressionWeights.isEmpty())
        assertNull(runtime.lookTarget)
    }

    @Test
    fun reloadClearsPreviousAvatarCommandState() = runTest {
        val runtime = HeadlessAvatarRuntime()
        runtime.load(model)
        runtime.setExpression(AvatarExpression.Happy)

        runtime.load(model.copy(id = "avatar-2"))

        assertTrue(runtime.expressionWeights.isEmpty())
        assertEquals(
            "avatar-2",
            assertIs<AvatarRuntimeState.Ready>(runtime.state.value).model.id,
        )
    }

    @Test
    fun failedLoadPublishesFailedStateAndRethrows() = runTest {
        val runtime = object : HeadlessAvatarRuntime() {
            override suspend fun loadCapabilities(model: AvatarModel): AvatarCapabilities =
                error("renderer exploded")
        }

        assertFailsWith<IllegalStateException> { runtime.load(model) }

        val failed = assertIs<AvatarRuntimeState.Failed>(runtime.state.value)
        assertEquals("renderer exploded", failed.message)
    }

    @Test
    fun disposeIsTerminal() = runTest {
        val runtime = HeadlessAvatarRuntime()
        runtime.load(model)

        runtime.dispose()

        assertEquals(AvatarRuntimeState.Idle, runtime.state.value)
        assertTrue(runtime.isDisposed)
        assertFailsWith<IllegalStateException> { runtime.load(model) }
        // Post-dispose commands are no-ops, not crashes.
        runtime.setExpression(AvatarExpression.Happy)
        runtime.update(0.016f)
    }
}
