package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

class TimelineSemanticFingerprintTest {
    private val instant = parseTimelineInstant("2026-01-01T00:00:00Z")

    @Test
    fun javaHashCollisionStringsHaveDistinctRawFingerprints() {
        // "Aa" and "BB" have the same length and String.hashCode on JVM.
        val aa = localState(content = "Aa")
        val bb = localState(content = "BB")

        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(aa.semanticFingerprint(), bb.semanticFingerprint())
        assertTrue(aa.semanticFingerprint().contains("2:Aa"))
        assertTrue(bb.semanticFingerprint().contains("2:BB"))
    }

    @Test
    fun attachmentMediaTypeAndFullBase64IdentityAreSemantic() {
        val png = localState(
            attachments = persistentListOf(
                MessageContentPart.Image("Aa/BB==", "image/png"),
            ),
        )
        val jpeg = localState(
            attachments = persistentListOf(
                MessageContentPart.Image("Aa/BB==", "image/jpeg"),
            ),
        )
        val differentBytes = localState(
            attachments = persistentListOf(
                MessageContentPart.Image("BB/Aa==", "image/png"),
            ),
        )

        assertNotEquals(png.semanticFingerprint(), jpeg.semanticFingerprint())
        assertNotEquals(png.semanticFingerprint(), differentBytes.semanticFingerprint())
        assertTrue(png.semanticFingerprint().contains("7:Aa/BB=="))
        assertTrue(png.semanticFingerprint().contains("9:image/png"))
    }

    @Test
    fun eventOrderIsSemanticWhileStableMapInsertionOrderIsNot() {
        val first = local("one", "one", 1.0)
        val second = local("two", "two", 2.0)
        val ordered = TimelineReducerState(Timeline("conversation", persistentListOf(first, second)))
        val reordered = TimelineReducerState(
            Timeline(
                "conversation",
                persistentListOf(
                    second.copy(position = 1.0),
                    first.copy(position = 2.0),
                ),
            ),
        )

        assertNotEquals(ordered.semanticFingerprint(), reordered.semanticFingerprint())
    }

    @Test
    fun lengthPrefixPortableLcgAndEffectFingerprintsHaveGoldenVectors() {
        assertEquals("2:Aa", semanticLengthPrefixed("Aa"))
        assertEquals("2:BB", semanticLengthPrefixed("BB"))
        val zero = PortableLcg(0)
        assertEquals(listOf(15, 4, 8, 10, 4, 10, 1, 14, 4, 11), List(10) { zero.nextInt(17) })
        val seeded = PortableLcg(73)
        assertEquals(listOf(4, 4, 9, 2, 5, 2, 7, 7), List(8) { seeded.nextInt(11) })

        val effect = TimelineReductionEffect.Send(PendingSend("Aa", "BB"))
        assertEquals(
            "23:TimelineReductionEffect1:{4:Send1:{7:pending1:{4:otid2:Aa7:content2:BB11:attachments1:01:}1:}1:}",
            effect.semanticFingerprint(),
        )
    }

    @Test
    fun rawFingerprintAndRedactedDiagnosticAreIntentionallyDifferent() {
        val raw = TimelineReductionEffect.Send(
            PendingSend("client", "do-not-log-this-body"),
        ).semanticFingerprint()

        assertTrue(raw.contains("do-not-log-this-body"))
        val diagnostic = redactSemanticFingerprint(raw)
        assertTrue(diagnostic.startsWith("len="))
        assertTrue(!diagnostic.contains("do-not-log-this-body"))
    }

    private fun localState(
        content: String = "content",
        attachments: kotlinx.collections.immutable.PersistentList<MessageContentPart.Image> = persistentListOf(),
    ) = TimelineReducerState(
        Timeline("conversation", persistentListOf(local("client", content, 1.0, attachments))),
    )

    private fun local(
        otid: String,
        content: String,
        position: Double,
        attachments: kotlinx.collections.immutable.PersistentList<MessageContentPart.Image> = persistentListOf(),
    ) = TimelineEvent.Local(
        position = position,
        otid = otid,
        content = content,
        sentAt = instant,
        deliveryState = DeliveryState.SENDING,
        attachments = attachments,
    )
}
