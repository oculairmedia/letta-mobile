package com.letta.mobile.quality.guardrails

import com.letta.mobile.quality.lintAt
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory

class ClientPersistenceRulesTest {
    @Test
    fun `the old per-device pins pattern is rejected`() {
        val pins = """
            package com.letta.mobile.data.pins

            import com.letta.mobile.data.storage.SecureSettingsStore

            class DevicePinStore(private val backing: SecureSettingsStore) {
                fun save(json: String) = backing.putDurableString(STORAGE_KEY, json)
                private companion object { const val STORAGE_KEY = "core.objectPins.v1" }
            }
        """.trimIndent()
        val hostHeld = """
            package com.letta.mobile.data.pins

            class HostPins(private val document: ShellStateDocument) {
                suspend fun pin(order: JsonElement, revision: Long?, client: String) =
                    document.propose("pins", order, revision, ShellStateActor.User(client))
            }
        """.trimIndent()
        assertEquals(1, ClientPersistenceOutsideClientStore().lint(pins).size)
        assertEquals(0, ClientPersistenceOutsideClientStore().lint(hostHeld).size)
    }

    @Test
    fun `platform persistence, databases and browser storage are rejected`() {
        val code = """
            package com.letta.mobile.channel

            import android.content.SharedPreferences
            import androidx.datastore.preferences.preferencesDataStore
            import androidx.room.Room
            import java.util.prefs.Preferences
            import com.russhwolf.settings.Settings

            class ChannelSync(context: Context) {
                private val prefs = context.getSharedPreferences("channel_sync_state", 0)
                private val db = Room.databaseBuilder(context, Db::class.java, "state.db").build()
                fun web() = window.localStorage.setItem("k", "v")
                fun tab() = sessionStorage.getItem("k")
                fun idb() = indexedDB.open("letta")
            }
        """.trimIndent()
        assertEquals(10, ClientPersistenceOutsideClientStore().lint(code).size)
    }

    @Test
    fun `state files and the shared settings ports are rejected`() {
        val code = """
            package com.letta.mobile.desktop.canvas

            class ArchiveStore(private val docs: KeyValueStore, prefs: SettingsPreferencesStore) {
                private val file = defaultDesktopStateDirectory().resolve("archived-canvases.txt")
                private val layout = JsonPreference(settings, "chat.dock.geometry", Geometry.serializer())
                fun android(context: Context) = File(context.filesDir, "drafts.json").writeText("{}")
            }
        """.trimIndent()
        assertEquals(5, ClientPersistenceOutsideClientStore().lint(code).size)
    }

    @Test
    fun `the client store and host packages may persist`() {
        val clientStore = """
            package com.letta.mobile.web.clientstore

            class LocalStorageBootstrap(private val delegate: SecureSettingsStore) {
                fun backend() = localStorage.getItem("letta.web.backend.server_url")
            }
        """.trimIndent()
        val host = """
            package com.letta.mobile.data.controller.node.iroh.voice

            class VoiceSetupStorage(private val directory: Path) {
                fun write(text: String) = Files.writeString(directory.resolve("voice-setup.json"), text)
                fun legacy(store: KeyValueStore) = store
            }
        """.trimIndent()
        val onDeviceRuntime = """
            package com.letta.mobile.runtime.local

            class EmbeddedAssets(private val context: Context) {
                val root = File(context.filesDir, "letta-code")
            }
        """.trimIndent()
        assertEquals(0, ClientPersistenceOutsideClientStore().lint(clientStore).size)
        assertEquals(0, ClientPersistenceOutsideClientStore().lint(host).size)
        assertEquals(0, ClientPersistenceOutsideClientStore().lint(onDeviceRuntime).size)
    }

    @Test
    fun `test sources are not checked`() {
        val code = """
            package com.letta.mobile.data.pins

            class PinsTest(private val store: SecureSettingsStore)
        """.trimIndent()
        val root = createTempDirectory("client-persistence").toFile()
        val path = "sharedLogic/src/commonTest/kotlin/PinsTest.kt"
        val findings = ClientPersistenceOutsideClientStore().lintAt(root, path, code)
        assertEquals(0, findings.size)
    }

    @Test
    fun `device facts need a non-blank justification`() {
        val bad = """
            val a = ClientStoreKind.DeviceFact("")
            val b = ClientStoreKind.DeviceFact(justification = "   ")
            val c = deviceFact("voice.host.audioAck", "", "Host audio acknowledgement")
        """.trimIndent()
        val good = """
            val a = ClientStoreKind.DeviceFact("Consent is given per device for its own microphone")
            val b = deviceFact("voice.host.audioAck", justification = MIC_CONSENT, summary = "Host audio ack")
        """.trimIndent()
        assertEquals(3, DeviceFactWithoutJustification().lint(bad).size)
        assertEquals(0, DeviceFactWithoutJustification().lint(good).size)
    }
}
