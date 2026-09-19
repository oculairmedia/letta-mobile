package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window exception handler decides whether the app lives or dies, so what counts as
 * recoverable is worth pinning down.
 *
 * It used to exit for every throwable, which is what closed the whole app when a new canvas was
 * opened: Compose measured a menu layer that had been disposed while the screen behind it was
 * replaced, and a lost frame became a lost session. The opposite mistake is worse, though - an
 * app that swallows every render error hides real faults - so this stays narrow, and these tests
 * are what keep it narrow.
 */
class RecoverableRenderErrorTest {

    @Test
    fun aDisposedSceneLayerIsRecoverable() {
        assertTrue(isRecoverableRenderError(IllegalArgumentException("RootNodeOwner is already disposed")))
    }

    @Test
    fun theMessageIsMatchedWhateverTheCase() {
        assertTrue(isRecoverableRenderError(IllegalArgumentException("RootNodeOwner IS ALREADY DISPOSED")))
    }

    @Test
    fun anyOtherIllegalArgumentIsStillFatal() {
        assertFalse(isRecoverableRenderError(IllegalArgumentException("width must be positive")))
    }

    @Test
    fun aDifferentExceptionWithTheSameWordsIsStillFatal() {
        // Only the render path throws this as an IllegalArgumentException. A disposed-something
        // IllegalStateException is a different fault and must still be reported.
        assertFalse(isRecoverableRenderError(IllegalStateException("the session is already disposed")))
    }

    @Test
    fun aThrowableWithNoMessageIsStillFatal() {
        assertFalse(isRecoverableRenderError(IllegalArgumentException()))
    }

    @Test
    fun anOutOfMemoryIsNeverSwallowed() {
        assertFalse(isRecoverableRenderError(OutOfMemoryError("heap")))
    }
}
