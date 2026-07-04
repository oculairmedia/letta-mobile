package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

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
    fun loadRecordsAnimationSourcesAndUnloadClearsThem() = runTest {
        val runtime = HeadlessAvatarRuntime()
        val animations = listOf(
            AvatarAnimationSource("wave", "file:///wave.vrma", AvatarAnimationFormat.VRMA),
            AvatarAnimationSource("dance", "file:///dance.fbx", AvatarAnimationFormat.FBX),
        )

        runtime.load(model, animations)
        assertEquals(animations, runtime.loadedAnimations)

        runtime.unload()
        assertTrue(runtime.loadedAnimations.isEmpty())
    }

    @Test
    fun loadWithoutAnimationsRecordsEmptyList() = runTest {
        val runtime = HeadlessAvatarRuntime()
        runtime.load(model)
        assertTrue(runtime.loadedAnimations.isEmpty())
    }

    @Test
    fun animationSourcesReachLoadCapabilitiesHook() = runTest {
        var received: List<AvatarAnimationSource>? = null
        val runtime = object : HeadlessAvatarRuntime() {
            override suspend fun loadCapabilities(
                model: AvatarModel,
                animations: List<AvatarAnimationSource>,
            ): AvatarCapabilities {
                received = animations
                return AvatarCapabilities()
            }
        }
        val animations = listOf(AvatarAnimationSource("wave", "file:///wave.vrma", AvatarAnimationFormat.VRMA))
        runtime.load(model, animations)
        assertEquals(animations, received)
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
            override suspend fun loadCapabilities(model: AvatarModel, animations: List<AvatarAnimationSource>): AvatarCapabilities =
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

    /** Counts onUnload calls so adapter-teardown contracts are assertable. */
    private class UnloadCountingRuntime : HeadlessAvatarRuntime() {
        var unloadCount = 0
            private set

        override fun onUnload() {
            unloadCount += 1
        }
    }

    @Test
    fun replacingALoadedAvatarFiresTheUnloadHook() = runTest {
        val runtime = UnloadCountingRuntime()

        runtime.load(model)
        assertEquals(0, runtime.unloadCount) // first load replaces nothing

        runtime.load(model.copy(id = "avatar-2"))
        assertEquals(1, runtime.unloadCount) // previous avatar torn down
    }

    @Test
    fun unloadAndDisposeFireTheUnloadHookOnlyWhileLive() = runTest {
        val runtime = UnloadCountingRuntime()

        runtime.unload() // idle — nothing to tear down
        assertEquals(0, runtime.unloadCount)

        runtime.load(model)
        runtime.unload()
        assertEquals(1, runtime.unloadCount)

        runtime.load(model)
        runtime.dispose() // dispose without prior unload must not leak
        assertEquals(2, runtime.unloadCount)
    }

    @Test
    fun commandsForUnsupportedCapabilitiesAreDropped() = runTest {
        val runtime = object : HeadlessAvatarRuntime() {
            var animationPlays = 0
            override suspend fun loadCapabilities(model: AvatarModel, animations: List<AvatarAnimationSource>) = AvatarCapabilities()
            override fun onPlayAnimation(animationId: String, loop: Boolean) {
                animationPlays += 1
            }
        }
        runtime.load(model)

        runtime.setExpression(AvatarExpression.Happy)
        runtime.setViseme(AvatarViseme.A, 1f)
        runtime.setMouthOpen(1f)
        runtime.setLookTarget(AvatarLookTarget.World(0f, 0f, 0f))
        runtime.playAnimation("Idle")
        runtime.setAccessoryEnabled("glasses", enabled = false)

        assertTrue(runtime.expressionWeights.isEmpty())
        assertTrue(runtime.visemeWeights.isEmpty())
        assertEquals(0f, runtime.mouthOpen)
        assertNull(runtime.lookTarget)
        assertEquals(0, runtime.animationPlays)
        assertTrue(runtime.disabledAccessoryIds.isEmpty())
    }

    @Test
    fun cancelledLoadReturnsToIdleInsteadOfFailed() = runTest {
        val runtime = object : HeadlessAvatarRuntime() {
            override suspend fun loadCapabilities(model: AvatarModel, animations: List<AvatarAnimationSource>): AvatarCapabilities =
                throw kotlinx.coroutines.CancellationException("scope torn down")
        }

        assertFailsWith<kotlinx.coroutines.CancellationException> { runtime.load(model) }

        // Cancellation is not a failure — no error state flashed at observers.
        assertEquals(AvatarRuntimeState.Idle, runtime.state.value)
    }

    @Test
    fun cameraFramingIsRecordedGatedAndResetOnUnload() = runTest {
        val runtime = HeadlessAvatarRuntime()

        runtime.setCameraFraming(AvatarCameraFraming.BUST) // not loaded -> dropped
        assertEquals(AvatarCameraFraming.FULL_BODY, runtime.cameraFraming)

        runtime.load(model)
        runtime.setCameraFraming(AvatarCameraFraming.HEADSHOT)
        assertEquals(AvatarCameraFraming.HEADSHOT, runtime.cameraFraming)

        runtime.unload()
        assertEquals(AvatarCameraFraming.FULL_BODY, runtime.cameraFraming)
    }

    @Test
    fun nonFiniteWeightsAreDroppedToZero() = runTest {
        val runtime = HeadlessAvatarRuntime()
        runtime.load(model)

        runtime.setExpression(AvatarExpression.Happy, Float.NaN)
        runtime.setViseme(AvatarViseme.A, Float.NaN)
        runtime.setMouthOpen(Float.NaN)
        runtime.setMouthOpen(Float.POSITIVE_INFINITY)

        assertEquals(0f, runtime.expressionWeights["happy"])
        assertEquals(0f, runtime.visemeWeights["aa"])
        assertEquals(1f, runtime.mouthOpen) // +Inf clamps to 1, NaN drops to 0
    }

    @Test
    fun failedLoadTearsDownPartialRendererResources() = runTest {
        var unloads = 0
        val runtime = object : HeadlessAvatarRuntime() {
            override suspend fun loadCapabilities(model: AvatarModel, animations: List<AvatarAnimationSource>): AvatarCapabilities =
                error("exploded after allocating scene resources")

            override fun onUnload() {
                unloads += 1
            }
        }

        assertFailsWith<IllegalStateException> { runtime.load(model) }

        assertIs<AvatarRuntimeState.Failed>(runtime.state.value)
        assertEquals(1, unloads) // partial resources were released
    }

    @Test
    fun staleLoadDoesNotOverwriteNewerState() = runTest {
        // First load suspends until released; second load completes first.
        val firstGate = CompletableDeferred<Unit>()
        var loadCount = 0
        val runtime = object : HeadlessAvatarRuntime() {
            override suspend fun loadCapabilities(model: AvatarModel, animations: List<AvatarAnimationSource>): AvatarCapabilities {
                loadCount += 1
                if (loadCount == 1) firstGate.await()
                return AvatarCapabilities(supportsHumanoid = true)
            }
        }

        val staleLoad = launch {
            runCatching { runtime.load(model) }
        }
        yield() // first load reaches the gate

        runtime.load(model.copy(id = "newer")) // supersedes while first suspended
        firstGate.complete(Unit) // stale load resumes
        staleLoad.join()

        // The newer model owns the state; the stale result was discarded.
        assertEquals(
            "newer",
            assertIs<AvatarRuntimeState.Ready>(runtime.state.value).model.id,
        )
    }

    @Test
    fun unloadDuringInFlightLoadWinsOverLateResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = object : HeadlessAvatarRuntime() {
            override suspend fun loadCapabilities(model: AvatarModel, animations: List<AvatarAnimationSource>): AvatarCapabilities {
                gate.await()
                return AvatarCapabilities()
            }
        }

        val load = launch { runCatching { runtime.load(model) } }
        yield()

        runtime.unload()
        gate.complete(Unit)
        load.join()

        assertEquals(AvatarRuntimeState.Idle, runtime.state.value)
    }
}
