package com.letta.mobile.avatar.catalog

import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarLicense
import com.letta.mobile.avatar.core.AvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AvatarCatalogCodecTest {
    @Test
    fun roundTripsEntries() {
        val entries = listOf(
            AvatarModel(
                id = "a",
                displayName = "Alpha",
                uri = "file:///a.vrm",
                format = AvatarFormat.VRM_1,
                license = AvatarLicense(licenseName = "CC0", allowRedistribution = true),
                sha256 = "abc",
            ),
        )
        assertEquals(entries, AvatarCatalogCodec.decode(AvatarCatalogCodec.encode(entries)))
    }

    @Test
    fun toleratesUnknownFieldsAndEmptyDocument() {
        assertEquals(
            emptyList(),
            AvatarCatalogCodec.decode("""{"schemaVersion":1,"entries":[],"future":true}"""),
        )
    }

    @Test
    fun rejectsNewerSchemaAndMalformedJson() {
        assertFailsWith<AvatarCatalogException> {
            AvatarCatalogCodec.decode("""{"schemaVersion":99,"entries":[]}""")
        }
        assertFailsWith<AvatarCatalogException> {
            AvatarCatalogCodec.decode("not json")
        }
    }
}
