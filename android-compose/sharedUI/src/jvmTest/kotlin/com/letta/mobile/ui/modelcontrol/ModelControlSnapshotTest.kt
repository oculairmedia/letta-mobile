@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectForm
import com.letta.mobile.ui.theme.LettaColorTokens
import com.letta.mobile.ui.theme.LettaDimens
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Light and dark renders of the picker (A), the Models sheet (B) and the
 * Providers settings pages (C) at desktop and phone sizes
 * (letta-mobile-w4q4p.6.1). Each test writes a PNG under
 * `build/snapshots/modelcontrol/` for review and asserts it was drawn; they
 * are review artifacts, not pixel goldens.
 */
class ModelControlSnapshotTest {
    private val outDir = File("build/snapshots/modelcontrol").apply { mkdirs() }

    private enum class Form(val width: Dp, val height: Dp) {
        DESKTOP(1040.dp, 700.dp),
        PHONE(400.dp, 860.dp),
    }

    private val noPicker = ModelPickerActions(onSelect = {}, onQueryChange = {}, onToggleGroup = {}, onRefresh = {}, onEditModels = {})
    private val noEdit = ModelsEditActions(onClose = {}, onQueryChange = {}, onExposedChange = {}, onAddProvider = {})
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
    fun picker() = renderAll("picker") { form ->
        Modal(form) { ModelPickerContent(ModelControlUiFixtures.pickerState(), noPicker) }
    }

    @Test
    fun pickerRefreshing() = render("picker-refreshing", Form.DESKTOP, dark = true) { form ->
        Modal(form) {
            ModelPickerContent(
                ModelControlUiFixtures.pickerState(refreshing = true, error = "Couldn't refresh models: upstream timed out"),
                noPicker,
            )
        }
    }

    @Test
    fun modelsSheet() = renderAll("models") { form ->
        Modal(form) { ModelsEditContent(ModelControlUiFixtures.managementState(), noEdit) }
    }

    @Test
    fun providersAccounts() = renderAll("providers-accounts") {
        ProviderSettingsPane(ModelControlUiFixtures.managementState(), noSettings, ProviderSettingsPage.ACCOUNTS, onPageChange = {})
    }

    @Test
    fun providersApiKeys() = renderAll("providers-apikeys") {
        val anthropic = ModelControlUiFixtures.providers.single { it.id == "anthropic" }
        val state = ModelControlUiFixtures.managementState().copy(form = ProviderConnectForm(anthropic).withValue("apiKey", "sk-ant-api03"))
        ProviderSettingsPane(state, noSettings, ProviderSettingsPage.API_KEYS, onPageChange = {})
    }

    @Test
    fun providersEndpoints() = renderAll("providers-endpoints") {
        ProviderSettingsPane(ModelControlUiFixtures.managementState(), noSettings, ProviderSettingsPage.ENDPOINTS, onPageChange = {})
    }

    private fun renderAll(name: String, content: @Composable (Form) -> Unit) {
        Form.entries.forEach { form ->
            listOf(false, true).forEach { dark -> render(name, form, dark, content) }
        }
    }

    private fun render(name: String, form: Form, dark: Boolean, content: @Composable (Form) -> Unit) =
        runDesktopComposeUiTest(width = 1700, height = 1400) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(SCALE)) {
                    MaterialTheme(colorScheme = scheme(dark)) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.size(form.width, form.height).testTag(FRAME),
                        ) { content(form) }
                    }
                }
            }
            save(this, "$name-${form.name.lowercase()}-${if (dark) "dark" else "light"}")
        }

    private fun save(test: DesktopComposeUiTest, file: String) {
        test.waitForIdle()
        val image = test.onNodeWithTag(FRAME).captureToImage().toAwtImage()
        val out = File(outDir, "$file.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0, "rendered $file")
    }

    /** Desktop: the real in-window modal. Phone: the sheet's frame, drawn in place (a real sheet is a popup). */
    @Composable
    private fun Modal(form: Form, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        when (form) {
            Form.DESKTOP -> ModelControlModal(ModelControlPresentation.Dialog, onDismiss = {}, content = content)
            Form.PHONE -> Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = LettaDimens.Space.xl, topEnd = LettaDimens.Space.xl),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().heightIn(max = form.height - LettaDimens.Space.xxl),
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                        content()
                    }
                }
            }
        }
    }

    /** The Letta palette's surface roles (DesktopMaterialTheme's), enough for review renders. */
    private fun scheme(dark: Boolean): ColorScheme = if (dark) {
        darkColorScheme(
            primary = Color(LettaColorTokens.DARK_PRIMARY),
            secondaryContainer = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH),
            onSecondaryContainer = Color(LettaColorTokens.DARK_ON_SURFACE),
            background = Color(LettaColorTokens.DARK_BACKGROUND),
            surface = Color(LettaColorTokens.DARK_SURFACE),
            onSurface = Color(LettaColorTokens.DARK_ON_SURFACE),
            onSurfaceVariant = Color(LettaColorTokens.DARK_ON_SURFACE_VARIANT),
            surfaceContainerLow = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_LOW),
            surfaceContainer = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_DEFAULT),
            surfaceContainerHigh = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH),
            surfaceContainerHighest = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGHEST),
            outlineVariant = Color(LettaColorTokens.DARK_OUTLINE_VARIANT),
            error = Color(LettaColorTokens.DARK_ERROR),
        )
    } else {
        lightColorScheme(
            primary = Color(LettaColorTokens.LIGHT_PRIMARY),
            secondaryContainer = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
            onSecondaryContainer = Color(LettaColorTokens.LIGHT_ON_SURFACE),
            background = Color(LettaColorTokens.LIGHT_BACKGROUND),
            surface = Color(LettaColorTokens.LIGHT_SURFACE),
            onSurface = Color(LettaColorTokens.LIGHT_ON_SURFACE),
            onSurfaceVariant = Color(LettaColorTokens.LIGHT_ON_SURFACE_VARIANT),
            surfaceContainerLow = Color(LettaColorTokens.LIGHT_SURFACE),
            surfaceContainer = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT),
            surfaceContainerHigh = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
            outlineVariant = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
            error = Color(LettaColorTokens.LIGHT_ERROR),
        )
    }

    private companion object {
        const val FRAME = "snapshot_frame"
        const val SCALE = 1.5f
        const val SCRIM = 0.45f
    }
}
