package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.TestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val FEATURE = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/Row.kt"
private const val COMPONENT = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/components/Shared.kt"
private const val THEME = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/theme/Tokens.kt"
private const val TEST = "sharedUI/src/commonTest/kotlin/com/letta/mobile/ui/chat/RowTest.kt"
private const val FOUNDATION = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/components/foundation/LettaCard.kt"
private const val PREVIEW = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/chat/RowPreview.kt"
private const val NOT_UI = "sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/Model.kt"

class MobileDesignSystemRulesTest {
    @Test
    fun `raw dimensions fail except zero and hairline`(@TempDir root: File) {
        val code = "val a = 12.dp\nval b = 14.sp\nval c = 0.dp\nval d = 1.dp\nval e = 2.5.dp\n"
        assertEquals(3, RawDimensionLiteral().lintAt(root, FEATURE, code).size)
        assertEquals(0, RawDimensionLiteral().lintAt(root, THEME, code).size)
        assertEquals(0, RawDimensionLiteral().lintAt(root, TEST, code).size)
        assertEquals(0, RawDimensionLiteral().lintAt(root, NOT_UI, code).size)
    }

    @Test
    fun `hex colours fail while theme roles pass`(@TempDir root: File) {
        val code = "val a = Color(0xFF2A2A2A)\nval b = MaterialTheme.colorScheme.primary\nval c = Color(red)\n"
        assertEquals(1, HardcodedComposeColor().lintAt(root, FEATURE, code).size)
        assertEquals(0, HardcodedComposeColor().lintAt(root, THEME, code).size)
        assertEquals(0, HardcodedComposeColor().lintAt(root, PREVIEW, code).size)
    }

    @Test
    fun `alpha below the floor fails`(@TempDir root: File) {
        val code = "val a = c.copy(alpha = 0.4f)\nval b = c.copy(alpha = 0.6f)\nval d = c.copy(alpha = level)\n"
        assertEquals(1, LowContentAlpha().lintAt(root, FEATURE, code).size)
        assertEquals(0, LowContentAlpha().lintAt(root, TEST, code).size)
    }

    @Test
    fun `corner shapes built at a call site fail`(@TempDir root: File) {
        val code = """
            val a = RoundedCornerShape(8.dp)
            val b = CutCornerShape(4.dp)
            val c = CircleShape
            val d = MaterialTheme.shapes.medium
        """.trimIndent()
        assertEquals(2, RawShapeLiteral().lintAt(root, FEATURE, code).size)
        assertEquals(2, RawShapeLiteral().lintAt(root, COMPONENT, code).size)
        assertEquals(0, RawShapeLiteral().lintAt(root, THEME, code).size)
        assertEquals(0, RawShapeLiteral().lintAt(root, TEST, code).size)
    }

    @Test
    fun `typography overrides fail while forwarded values and named styles pass`(@TempDir root: File) {
        val bad = """
            fun a() = Text("x", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            fun b() = Text("x", fontFamily = if (code) FontFamily.Monospace else null)
            fun c() = Text("x", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            fun d() = Text("x", style = LettaType.bodyMono.copy(color = tint))
            fun e() = Text("x", letterSpacing = LETTER_SPACING, lineHeight = LettaTokens.line)
            fun f() { val weight = FontWeight.Bold; Text("x", fontWeight = weight) }
        """.trimIndent()
        val good = """
            fun a(weight: FontWeight) = Text("x", fontWeight = weight, style = MaterialTheme.typography.bodySmall)
            fun b() = TextModel(fontFamily = shape.fontFamilyKey)
            fun c() = Text("x", style = LettaType.bodyMono, color = tint)
            fun d() = palette.copy(fileType = fileType.ext)
        """.trimIndent()
        assertEquals(8, TypographyOverride().lintAt(root, FEATURE, bad).size)
        assertEquals(0, TypographyOverride().lintAt(root, FEATURE, good).size)
        assertEquals(8, TypographyOverride().lintAt(root, COMPONENT, bad).size)
        assertEquals(0, TypographyOverride().lintAt(root, THEME, bad).size)
    }

    @Test
    fun `disclosure arrows outside shared components fail`(@TempDir root: File) {
        val code = """
            import androidx.compose.material.icons.filled.ExpandMore
            val a = LettaIcons.ChevronDown
            val b = Icons.Default.ExpandMore
            val c = Lucide.ChevronsUpDown
            val d = Icons.AutoMirrored.Filled.KeyboardArrowRight
            val e = LettaIcons.ArrowBack
            val f = LettaIcons.Close
            val g = LettaIcons
                .ChevronRight
        """.trimIndent()
        assertEquals(5, RawDisclosureIcon().lintAt(root, FEATURE, code).size)
        assertEquals(0, RawDisclosureIcon().lintAt(root, COMPONENT, code).size)
        assertEquals(0, RawDisclosureIcon().lintAt(root, THEME, code).size)
        val registry = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/icons/LettaIcons.kt"
        assertEquals(0, RawDisclosureIcon().lintAt(root, registry, code).size)
    }

    @Test
    fun `DisclosureChevron placed outside shared components fails`(@TempDir root: File) {
        val code = "fun a() = DisclosureChevron(expanded = open)\n" +
            "fun b() = DisclosureChevronDefaults.targetRotation(x, y)\n"
        assertEquals(1, DisclosureOutsideSharedRow().lintAt(root, FEATURE, code).size)
        assertEquals(0, DisclosureOutsideSharedRow().lintAt(root, COMPONENT, code).size)
        assertEquals(0, DisclosureOutsideSharedRow().lintAt(root, TEST, code).size)
    }

    @Test
    fun `status icons drawn by hand fail`(@TempDir root: File) {
        val code = """
            fun a() = Icon(LettaIcons.CheckCircle, contentDescription = null)
            fun b() = Icon(imageVector = if (ok) LettaIcons.Check else LettaIcons.Error, contentDescription = null)
            fun c() = Image(rememberVectorPainter(Icons.Outlined.Warning), null)
            fun d() = Icon(LettaIcons.Close, contentDescription = null)
            fun e() = Icon(LettaIcons.AccountCircle, contentDescription = null)
            fun f() = Button(onClick = {}) { Text("Check") }
            fun g() = Icon(
                LettaIcons
                    .CheckCircle,
                contentDescription = null,
            )
        """.trimIndent()
        assertEquals(4, RawStatusGlyph().lintAt(root, FEATURE, code).size)
        assertEquals(0, RawStatusGlyph().lintAt(root, COMPONENT, code).size)
        assertEquals(0, RawStatusGlyph().lintAt(root, PREVIEW, code).size)
    }

    @Test
    fun `clickable without a role fails`(@TempDir root: File) {
        val code = """
            val a = Modifier.clickable { open() }
            val b = Modifier.clickable(onClick = { open() })
            val c = Modifier.clickable(role = Role.Button) { open() }
            val d = Modifier.clickable(true, null, Role.Switch) { open() }
            val e = Modifier.combinedClickable(onClick = {})
            val f = Modifier.clickable(role = null) { open() }
            val g = Modifier.clickable(true, null, buttonRole) { open() }
            val h = Modifier.clickable(source, null, true, null, buttonRole) { open() }
            val i = Modifier.clickable(source, null) { open() }
            val j = Modifier.clickable(source, null, true) { open() }
            val k = Modifier.clickable(source, null, enabled) { open() }
        """.trimIndent()
        assertEquals(7, BareClickable().lintAt(root, FEATURE, code).size)
        assertEquals(0, BareClickable().lintAt(root, COMPONENT, code).size)
        assertEquals(0, BareClickable().lintAt(root, NOT_UI, code).size)
    }

    @Test
    fun `checkout directories named src or build do not change the verdict`(@TempDir root: File) {
        val code = "val a = RoundedCornerShape(8.dp)\n"
        val checkout = "home/src/build/letta-mobile/android-compose/"
        assertEquals(1, RawShapeLiteral().lintAt(root, checkout + FEATURE, code).size)
        assertEquals(0, RawShapeLiteral().lintAt(root, checkout + TEST, code).size)
    }

    @Test
    fun `an includes list limits a rule to the listed paths`(@TempDir root: File) {
        val config = TestConfig("includes" to listOf("**/ui/clean/**"))
        val code = "val a = Modifier.clickable { open() }\n"
        val clean = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/clean/Row.kt"
        assertEquals(1, BareClickable(config).lintAt(root, clean, code).size)
        assertEquals(0, BareClickable(config).lintAt(root, FEATURE, code).size)
    }

    @Test
    fun `transparent stops and named alpha tokens pass while token files may define low alphas`(@TempDir root: File) {
        val code = """
            val a = c.copy(alpha = 0f)
            val b = c.copy(alpha = 0.0f)
            val d = c.copy(alpha = 0)
            val e = c.copy(alpha = LettaAlpha.disabled)
            val f = c.copy(alpha = 0.38f)
        """.trimIndent()
        assertEquals(1, LowContentAlpha().lintAt(root, FEATURE, code).size)
        assertEquals(0, LowContentAlpha().lintAt(root, THEME, code).size)
    }

    @Test
    fun `combinedClickable, selectable and toggleable need a role too`(@TempDir root: File) {
        val code = """
            val a = Modifier.combinedClickable(onLongClick = {}) { open() }
            val b = Modifier.selectable(selected = on, onClick = { pick() })
            val c = Modifier.toggleable(value = on, onValueChange = { flip(it) })
            val d = Modifier.combinedClickable(role = Role.Button, onLongClick = {}) { open() }
            val e = Modifier.selectable(on, true, Role.RadioButton) { pick() }
            val f = Modifier.toggleable(on, source, null, true, Role.Switch) { flip(it) }
        """.trimIndent()
        assertEquals(3, BareClickable().lintAt(root, FEATURE, code).size)
    }

    @Test
    fun `a status icon held in a local of the same function is followed`(@TempDir root: File) {
        val code = """
            fun a(ok: Boolean) {
                val icon = when (ok) {
                    true -> LettaIcons.CheckCircle
                    false -> LettaIcons.Error
                }
                Icon(icon, contentDescription = null)
            }
            fun b() {
                val glyph = LettaIcons.Warning
                Image(rememberVectorPainter(glyph), null)
            }
            fun c(icon: ImageVector) = Icon(icon, contentDescription = null)
            fun d() {
                val icon = LettaIcons.Close
                Icon(icon, contentDescription = null)
            }
        """.trimIndent()
        assertEquals(1, RawStatusGlyph().lintAt(root, FEATURE, code).size)
    }

    @Test
    fun `composable components in theme paths fail while value accessors and theme wrappers pass`(@TempDir root: File) {
        val code = """
            @Composable
            fun LettaPill(text: String) { Text(text) }
            @Composable
            fun LettaSectionLabel(text: String): Unit = Text(text)
            @Composable
            @ReadOnlyComposable
            fun pillColor(): Color { return MaterialTheme.colorScheme.primary }
            @Composable
            fun mutedTint() = MaterialTheme.colorScheme.onSurfaceVariant
            @Composable
            fun LettaTheme(content: @Composable () -> Unit) { MaterialTheme(content = content) }
            @Composable
            fun ProvidePolicy(content: @Composable () -> Unit) {
                CompositionLocalProvider(LocalPolicy provides p, content)
            }
            fun helper() { }
        """.trimIndent()
        val tokens = "designsystem/src/main/java/com/letta/mobile/ui/tokens/Pills.kt"
        assertEquals(2, ComponentInExemptPath().lintAt(root, THEME, code).size)
        assertEquals(2, ComponentInExemptPath().lintAt(root, tokens, code).size)
        assertEquals(0, ComponentInExemptPath().lintAt(root, COMPONENT, code).size)
        assertEquals(0, ComponentInExemptPath().lintAt(root, FEATURE, code).size)
    }

    @Test
    fun `raw material cards fail outside the foundation package`(@TempDir root: File) {
        val explicit = """
            import androidx.compose.material3.Card
            import androidx.compose.material3.OutlinedCard as Outlined
            fun a() = Card(modifier = Modifier) {}
            fun b() = Outlined {}
        """.trimIndent()
        val wildcard = "import androidx.compose.material3.*\nfun a() = ElevatedCard {}\nfun b() = Card {}\n"
        val qualified = "fun a() = androidx.compose.material3.Card {}\n"
        val projectType = """
            import com.letta.mobile.data.canvas.Card
            fun a() = Card(label = "x")
            fun b() = LettaCard {}
        """.trimIndent()
        assertEquals(2, RawMaterialCard().lintAt(root, FEATURE, explicit).size)
        assertEquals(2, RawMaterialCard().lintAt(root, FEATURE, wildcard).size)
        assertEquals(1, RawMaterialCard().lintAt(root, FEATURE, qualified).size)
        assertEquals(0, RawMaterialCard().lintAt(root, FEATURE, projectType).size)
        assertEquals(2, RawMaterialCard().lintAt(root, COMPONENT, explicit).size)
        assertEquals(2, RawMaterialCard().lintAt(root, THEME, explicit).size)
        assertEquals(0, RawMaterialCard().lintAt(root, FOUNDATION, explicit).size)
        assertEquals(0, RawMaterialCard().lintAt(root, TEST, explicit).size)
        assertEquals(0, RawMaterialCard().lintAt(root, NOT_UI, explicit).size)
    }
}
