package com.letta.mobile.data.model

import com.letta.mobile.data.repository.LettaConfigPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.9: a saved Letta Cloud config migrates to a Remote host on read, never crashing the load. */
class LettaConfigMigrationTest {
    @Test
    fun theRetiredCloudModeReadsAsRemoteHostAndKnownModesAsThemselves() {
        assertEquals(LettaConfig.Mode.SELF_HOSTED, LettaConfigMigration.modeOf("CLOUD"))
        assertEquals(LettaConfig.Mode.SELF_HOSTED, LettaConfigMigration.modeOf(" cloud "))
        assertEquals(LettaConfig.Mode.SELF_HOSTED, LettaConfigMigration.modeOf("SELF_HOSTED"))
        assertEquals(LettaConfig.Mode.LOCAL, LettaConfigMigration.modeOf("LOCAL"))
        assertNull(LettaConfigMigration.modeOf("SOMETHING_ELSE"))
        assertNull(LettaConfigMigration.modeOf(null))
    }

    @Test
    fun aCloudConfigOnLettaCloudDropsTheCloudAddressAndKeyAndSaysWhy() {
        val stored = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://api.letta.com", accessToken = "sk-cloud")

        val migrated = LettaConfigMigration.migrate("CLOUD", stored)

        assertEquals(LettaConfig.Mode.SELF_HOSTED, migrated.mode)
        assertEquals("", migrated.serverUrl)
        assertNull(migrated.accessToken)
        assertEquals(LettaConfigMigration.CLOUD_NOTICE, migrated.migrationNotice)
        assertEquals("c1", migrated.id)
    }

    @Test
    fun aCloudConfigWithItsOwnAddressKeepsItAsTheRemoteHost() {
        val stored = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "wss://host.example/ws", accessToken = "t")

        val migrated = LettaConfigMigration.migrate("CLOUD", stored)

        assertEquals("wss://host.example/ws", migrated.serverUrl)
        assertEquals("t", migrated.accessToken)
        assertEquals(LettaConfigMigration.CLOUD_NOTICE, migrated.migrationNotice)
    }

    @Test
    fun otherModesAreLeftAlone() {
        val stored = LettaConfig(id = "c1", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://api.letta.com")

        assertEquals(stored, LettaConfigMigration.migrate("SELF_HOSTED", stored))
        assertNull(LettaConfigMigration.migrate("SELF_HOSTED", stored).migrationNotice)
    }

    @Test
    fun lettaCloudHostsAreRecognisedWhateverTheScheme() {
        assertTrue(LettaConfigMigration.isLettaCloudUrl("https://api.letta.com"))
        assertTrue(LettaConfigMigration.isLettaCloudUrl("https://app.letta.com/v1/"))
        assertTrue(LettaConfigMigration.isLettaCloudUrl("api.letta.com:443"))
        assertFalse(LettaConfigMigration.isLettaCloudUrl("https://letta.example.com"))
        assertFalse(LettaConfigMigration.isLettaCloudUrl("iroh://abc@10.0.0.2:4433"))
        assertFalse(LettaConfigMigration.isLettaCloudUrl(""))
    }

    @Test
    fun storedConfigListsWithACloudEntryStillLoadEveryEntry() {
        val json = """[
            {"id":"cloud","mode":"CLOUD","serverUrl":"https://api.letta.com","accessToken":"sk-cloud"},
            {"id":"iroh","mode":"SELF_HOSTED","serverUrl":"iroh://abc@10.0.0.2:4433"},
            {"id":"local","mode":"LOCAL","serverUrl":"local-lettacode://device"}
        ]"""

        val configs = LettaConfigPersistence.decodeList(json)

        assertEquals(listOf("cloud", "iroh", "local"), configs.map { it.id })
        assertEquals(LettaConfig.Mode.SELF_HOSTED, configs[0].mode)
        assertNull(configs[0].accessToken)
        assertEquals(LettaConfigMigration.CLOUD_NOTICE, configs[0].migrationNotice)
        assertNull(configs[1].migrationNotice)
        assertEquals(LettaConfig.Mode.LOCAL, configs[2].mode)
    }

    @Test
    fun aMigratedConfigIsWrittenBackWithoutTheNoticeOrTheRetiredMode() {
        val migrated = LettaConfigPersistence.decodeList("""[{"id":"cloud","mode":"CLOUD","serverUrl":"https://api.letta.com"}]""")

        val written = LettaConfigPersistence.encodeList(migrated)

        assertFalse(written.contains("CLOUD"))
        assertFalse(written.contains("notice", ignoreCase = true))
        assertEquals(LettaConfig.Mode.SELF_HOSTED, LettaConfigPersistence.decodeList(written).single().mode)
    }
}
