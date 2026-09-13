package com.letta.mobile.feature.chat.zoom

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * letta-mobile-tgypm. The timeline scales its type set once, inside TimelineZoomScope. A call site
 * that multiplies LocalChatFontScale onto a style read from that set applies the zoom twice, so at
 * a committed 1.5x the text lands at 2.25x.
 *
 * This is a source scan rather than a render assertion on purpose: the defect is invisible per
 * component, because each one looks correct in isolation. It only shows when a component is drawn
 * inside the scope, and no single rendered test covers every component that could regress. Five of
 * these were found by reading, after the rendered tests were already green - inline markdown,
 * streaming assistant text, timestamps, latency and reasoning titles.
 */
class NoDoubleScaleContractTest {

    private val roots = listOf(
        "../designsystem/src/main/java/com/letta/mobile/ui",
        "../feature-chat/src/main/java/com/letta/mobile/feature/chat",
        "../sharedUI/src/commonMain/kotlin/com/letta/mobile/ui",
    )

    /** The one place allowed to multiply: the builder that produces the scaled set itself. */
    private val allowed = setOf("ChatTheme.kt")

    @Test fun nothingScalesAStyleThatTheZoomScopeAlreadyScaled() {
        val offenders = mutableListOf<String>()
        roots.map(::File).filter(File::exists).forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                if (file.name in allowed) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    val multiplies = line.contains(".scaledBy(LocalChatFontScale.current)") ||
                        line.contains(".scaledBy(fontScale)")
                    if (multiplies) offenders += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertEquals(
            "these multiply the timeline zoom onto a style TimelineZoomScope already scaled",
            emptyList<String>(),
            offenders,
        )
    }
}
