package com.letta.mobile.data.transport.iroh

import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.canvas.NotebookLocalStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Loads the published Automerge Android JNI inside the actual app process. */
class AutomergeAndroidDeviceTest {
    @Test
    fun nativeNotebookDocumentSurvivesRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = Files.createTempDirectory(context.filesDir.toPath(), "automerge-device-")
        val id = NotebookLocalStore(directory, "android-device-test").use { store ->
            store.create("Device note").also { store.insertMarkdown(it, 0, "offline") }
        }
        NotebookLocalStore(directory, "android-device-test").use { store ->
            val document = requireNotNull(store.read(id))
            assertEquals("Device note", document.title)
            assertEquals("offline", document.markdown)
        }
    }
}
