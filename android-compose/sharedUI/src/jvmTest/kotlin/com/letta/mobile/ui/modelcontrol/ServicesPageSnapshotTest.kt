@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Services page inside Providers at 360, 700, 760 and 1200 dp, light and dark, with fake
 * services: the list, an open form and a read-only device. Writes PNGs under
 * `build/snapshots/servicecredentials/` for review; no secret is ever typed into a render.
 */
class ServicesPageSnapshotTest {
    private val outDir = File("build/snapshots/servicecredentials").apply { mkdirs() }
    private val widths = listOf(360, 700, 760, 1200)
    private val noSettings = ProviderSettingsActions(
        management = ProviderManagementActions(
            onRefresh = {}, onQueryChange = {}, onFilterChange = {}, onToggleExpanded = {}, onExposedChange = {},
            onProviderExposedChange = { _, _ -> }, onConnect = {}, onDisconnect = {},
            form = ProviderFormActions(onChange = {}, onSubmit = {}, onDismiss = {}),
            onConfirmDisconnect = {}, onDismissDisconnect = {},
        ),
        onOpenSignIn = {}, onCheckSignIn = {}, onDismissSignIn = {}, onEdit = {},
    )

    @Test
    fun list() = renderAll("services-list") { ServicesPane(ServiceCredentialsUiFixtures.state()) }

    @Test
    fun editing() = renderAll("services-editing") {
        ServicesPane(ServiceCredentialsUiFixtures.state(editing = ServiceCredentialsUiFixtures.speech.serviceId))
    }

    @Test
    fun readOnly() = renderAll("services-read-only") { ServicesPane(ServiceCredentialsUiFixtures.state(canManage = false)) }

    @Composable
    private fun ServicesPane(state: com.letta.mobile.data.servicecredentials.ServiceCredentialsState) {
        val binding = ServiceCredentialsBinding(state, ServiceCredentialsUiFixtures.Recorder().actions)
        val providers = ModelControlUiFixtures.managementState()
        ProviderSettingsPane(providers, noSettings, initialPage = ProviderSettingsPage.SERVICES, services = binding)
    }

    private fun renderAll(name: String, content: @Composable () -> Unit) {
        widths.forEach { width ->
            listOf(false, true).forEach { dark -> render("$name-${width}dp-${if (dark) "dark" else "light"}", width, dark, content) }
        }
    }

    private fun render(file: String, width: Int, dark: Boolean, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width = 1300, height = 1000) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    val frame = Modifier.size(width.dp, 900.dp).testTag(FRAME)
                    Surface(color = MaterialTheme.colorScheme.background, modifier = frame) { content() }
                }
            }
        }
        waitForIdle()
        val out = File(outDir, "$file.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0, "rendered $file")
    }

    private companion object {
        const val FRAME = "services_frame"
    }
}
