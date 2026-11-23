package com.letta.mobile.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * The Android window and splash colours are XML resources, because they paint before Compose runs and so cannot read
 * the design tokens. This keeps each one equal to the token it mirrors: when the palette moved to neutral greys the
 * resources kept the old navy and showed it in the window corners and the splash.
 */
class SplashColourMirrorTest {
    private val compose: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot"))).resolve("android-compose")
    private val tokens: String = compose.resolve("sharedLogic/src/commonMain/kotlin/com/letta/mobile/ui/theme/ThemeTokens.kt").readText()

    @Test
    fun `light window and splash colours equal their tokens`() {
        assertMirrors("values", mapOf(
            "letta_background" to "LIGHT_BACKGROUND",
            "letta_on_background" to "LIGHT_ON_SURFACE",
            "letta_mark_stone" to "LIGHT_MARK_STONE",
            "letta_mark_pebble" to "LIGHT_PRIMARY",
        ))
    }

    @Test
    fun `dark window and splash colours equal their tokens`() {
        assertMirrors("values-night", mapOf(
            "letta_background" to "DARK_BACKGROUND",
            "letta_on_background" to "DARK_ON_SURFACE",
            "letta_mark_stone" to "DARK_MARK_STONE",
            "letta_mark_pebble" to "DARK_PRIMARY",
        ))
    }

    private fun assertMirrors(qualifier: String, pairs: Map<String, String>) {
        val xml = compose.resolve("app/src/main/res/$qualifier/colors.xml").readText()
        pairs.forEach { (resource, token) ->
            assertEquals(tokenArgb(token), resourceArgb(xml, resource), "$qualifier/$resource must equal $token")
        }
    }

    private fun resourceArgb(xml: String, name: String): String =
        requireNotNull(Regex("""<color name="$name">#([0-9A-Fa-f]{8})</color>""").find(xml)) { "no $name colour" }
            .groupValues[1].uppercase()

    private fun tokenArgb(name: String): String =
        requireNotNull(Regex("""const val $name = 0x([0-9A-Fa-f]{8})""").find(tokens)) { "no $name token" }
            .groupValues[1].uppercase()
}
