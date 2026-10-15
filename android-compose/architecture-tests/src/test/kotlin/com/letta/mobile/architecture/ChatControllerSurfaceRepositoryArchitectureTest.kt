package com.letta.mobile.architecture

import com.letta.mobile.architecture.CountRatchetBaseline.Shrink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * letta-mobile-by4vm.80: the per-platform chat state owners only shrink. Android's
 * AdminChatViewModel and desktop's DesktopChatController are being collapsed onto the shared
 * `TimelineChatSessionPort` (letta-mobile-by4vm.14); a new public function or property on either
 * belongs in sharedLogic behind the port instead. The count is lowered in the same change that
 * moves a member out (stale lines fail).
 *
 * The name ends in `RepositoryArchitectureTest` so the `architectureTest` production-scan filter
 * runs it.
 */
class ChatControllerSurfaceRepositoryArchitectureTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val compose: Path = projectRoot.resolve("android-compose")

    @Test
    fun `chat state owners gain no public members`() {
        val actual = CONTROLLERS.associateWith { relative ->
            val file = compose.resolve(relative)
            check(file.exists()) { "$relative moved; update $BASELINE and this test's file list" }
            publicMemberCount(file.readLines())
        }
        val baseline = CountRatchetBaseline.read(compose.resolve("architecture-tests/$BASELINE"))
        val drift = CountRatchetBaseline.drift(actual, baseline, BASELINE, Shrink.MUST_UPDATE)
        check(drift.isEmpty()) {
            (drift + "Current counts:\n" + CountRatchetBaseline.render(actual)).joinToString(separator = "\n")
        }
    }

    @Test
    fun `counts public class members and skips private, internal, overrides and nested scopes`() {
        val source = """
            class Controller(private val scope: Scope) {
                val state: StateFlow<State> = _state
                var draft: String = ""
                fun send() = Unit
                suspend fun load() = Unit
                @Composable fun Render() = Unit
                private val _state = MutableStateFlow(State())
                internal fun helper() = Unit
                protected open fun hook() = Unit
                override fun close() = Unit
                private fun inner() {
                    fun nested() = Unit
                    val local = 1
                }
                companion object {
                    fun create() = Controller()
                }
            }
        """.trimIndent().lines()

        assertEquals(5, publicMemberCount(source))
    }

    internal companion object {
        const val BASELINE = "chat-controller-surface-baseline.txt"

        val CONTROLLERS = listOf(
            "desktop/src/main/kotlin/com/letta/mobile/desktop/chat/DesktopChatController.kt",
            "feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/AdminChatViewModel.kt",
        )

        private val hiddenModifiers = setOf("private", "internal", "protected", "override")

        /** A class-body member: four spaces deep, optional annotations and modifiers, then fun/val/var. */
        private val member = Regex("""^ {4}((?:@\w+(?:\([^)]*\))? )*(?:[a-z]+ )*)(fun|val|var) """)

        /** Public non-override functions and properties declared directly in a top-level class body. */
        fun publicMemberCount(lines: List<String>): Int = lines.count { line ->
            val modifiers = member.find(line)?.groupValues?.get(1) ?: return@count false
            modifiers.split(' ').none { it in hiddenModifiers }
        }
    }
}
