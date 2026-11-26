package com.letta.mobile.ui.mascot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.rive.core.RiveWorker
import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.avatar.core.model.MascotShape
import com.letta.mobile.avatar.rive.AndroidMascotScene
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Rive worker's per-vsync message poll follows the surfaces that are playing, not the ones
 * merely composed: a mascot frozen at rest must let the frame clock go quiet, and any surface
 * that starts playing again must bring the poll back.
 *
 * The scenes are stand-ins; binding one fails and the Rive surface draws nothing, which leaves
 * exactly the host's own bookkeeping under test (no native runtime on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidMascotHostPollTest {
    @get:Rule val compose = createComposeRule()

    private val host = AndroidMascotHost(
        worker = mockk<RiveWorker>(relaxed = true),
        resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources,
        stillsDirectory = Files.createTempDirectory("mascot-stills").toFile(),
    )

    private fun entry(shape: MascotShape): AndroidMascotEntry {
        val scene = mockk<AndroidMascotScene>(relaxed = true)
        // As a closed worker does: the surface cannot bind an artboard and draws nothing.
        every { scene.file } throws IllegalStateException("no native Rive on the JVM")
        return AndroidMascotEntry(scene, RiveAvatarRuntime(scene.sink), MascotIdentity(shape, TEST_COLOR))
    }

    @Test
    fun aSurfaceThatStopsPlayingStopsThePollAndResumingBringsItBack() {
        val mascot = entry(MascotShape.CIRCLE)
        var playing by mutableStateOf(true)
        compose.setContent { host.Surface(mascot, Modifier, playing = playing) }
        compose.waitForIdle()
        assertTrue("a playing surface polls", host.pollNeeded)

        playing = false
        compose.waitForIdle()
        assertFalse("a surface frozen at rest must not hold the poll", host.pollNeeded)

        playing = true
        compose.waitForIdle()
        assertTrue("playing again resumes the poll", host.pollNeeded)
    }

    @Test
    fun thePollRunsUntilEveryPlayingSurfaceHasRested() {
        val first = entry(MascotShape.CIRCLE)
        val second = entry(MascotShape.BLOB)
        var firstPlaying by mutableStateOf(true)
        var secondPlaying by mutableStateOf(true)
        compose.setContent {
            host.Surface(first, Modifier, playing = firstPlaying)
            host.Surface(second, Modifier, playing = secondPlaying)
        }
        compose.waitForIdle()
        assertTrue(host.pollNeeded)

        firstPlaying = false
        compose.waitForIdle()
        assertTrue("one surface still plays", host.pollNeeded)

        secondPlaying = false
        compose.waitForIdle()
        assertFalse("both rest: no poll", host.pollNeeded)

        secondPlaying = true
        compose.waitForIdle()
        assertTrue("a wake on either surface resumes the poll", host.pollNeeded)
    }

    @Test
    fun aRestingSurfaceThatLeavesCompositionLeavesTheCountBalanced() {
        val mascot = entry(MascotShape.CIRCLE)
        var playing by mutableStateOf(true)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) host.Surface(mascot, Modifier, playing = playing) }
        compose.waitForIdle()
        playing = false
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        assertFalse(host.pollNeeded)

        shown = true
        playing = true
        compose.waitForIdle()
        assertTrue("a new surface that plays polls again", host.pollNeeded)
    }

    private companion object {
        const val TEST_COLOR = 0xFF00AA88.toInt()
    }
}
