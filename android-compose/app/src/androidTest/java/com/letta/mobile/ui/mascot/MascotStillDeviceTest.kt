package com.letta.mobile.ui.mascot

import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a device, through the real Rive runtime: each identity's still is rendered offscreen with the
 * state machine settled, so two identities come out in their own colours (a paused first frame
 * showed the file's default for every one), and each is saved to the cache directory.
 */
@RunWith(AndroidJUnit4::class)
class MascotStillDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val violet = MascotIdentity(MascotShape.CIRCLE, argb = 0xFF7C3AED.toInt())
    private val amber = MascotIdentity(MascotShape.ROUNDED_SQUARE, argb = 0xFFF59E0B.toInt())

    @Test
    fun eachIdentityIsCapturedInItsOwnLook() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "mascot-stills").deleteRecursively()
        var host: MascotHost = NoMascotHost
        compose.setContent { host = rememberAndroidMascotHost() }
        compose.waitUntil(timeoutMillis = 30_000) { host.available }
        val stills = assertNotNull(host.stills).let { host.stills!! }

        runBlocking {
            stills.ensure(violet)
            stills.ensure(amber)
        }
        compose.waitUntil(timeoutMillis = 30_000) { stills.get(violet) != null && stills.get(amber) != null }

        val saved = File(context.cacheDir, "mascot-stills").listFiles().orEmpty().filter { it.extension == "png" }
        assertTrue("both stills are saved, found ${saved.map { it.name }}", saved.size == 2)
        fun dominant(identity: MascotIdentity): Int {
            val file = saved.single { it.name == "${stills.keyOf(identity)}.png" }
            val bitmap = BitmapFactory.decodeFile(file.path)
            // The body fills the middle of the frame, below the eye: sample there.
            return bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.7f).toInt())
        }
        val v = dominant(violet)
        val a = dominant(amber)
        assertTrue("the violet mascot is violet, got ${Integer.toHexString(v)}", (v shr 16 and 0xFF) in 80..180 && (v and 0xFF) > 180)
        assertTrue("the amber mascot is amber, got ${Integer.toHexString(a)}", (a shr 16 and 0xFF) > 200 && (a and 0xFF) < 90)
    }
}
