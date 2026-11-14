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
        """.trimIndent()
        val good = """
            fun a(weight: FontWeight) = Text("x", fontWeight = weight, style = MaterialTheme.typography.bodySmall)
            fun b() = TextModel(fontFamily = shape.fontFamilyKey)
            fun c() = Text("x", style = LettaType.bodyMono, color = tint)
            fun d() = palette.copy(fileType = fileType.ext)
        """.trimIndent()
        assertEquals(7, TypographyOverride().lintAt(root, FEATURE, bad).size)
        assertEquals(0, TypographyOverride().lintAt(root, FEATURE, good).size)
        assertEquals(7, TypographyOverride().lintAt(root, COMPONENT, bad).size)
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
        """.trimIndent()
        assertEquals(4, RawDisclosureIcon().lintAt(root, FEATURE, code).size)
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
        """.trimIndent()
        assertEquals(3, RawStatusGlyph().lintAt(root, FEATURE, code).size)
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
        """.trimIndent()
        assertEquals(2, BareClickable().lintAt(root, FEATURE, code).size)
        assertEquals(0, BareClickable().lintAt(root, COMPONENT, code).size)
        assertEquals(0, BareClickable().lintAt(root, NOT_UI, code).size)
    }

    @Test
    fun `an includes list limits a rule to the listed paths`(@TempDir root: File) {
        val config = TestConfig("includes" to listOf("**/ui/clean/**"))
        val code = "val a = Modifier.clickable { open() }\n"
        val clean = "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/clean/Row.kt"
        assertEquals(1, BareClickable(config).lintAt(root, clean, code).size)
        assertEquals(0, BareClickable(config).lintAt(root, FEATURE, code).size)
    }
}
