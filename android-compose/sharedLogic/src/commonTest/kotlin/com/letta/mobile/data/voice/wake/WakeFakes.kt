package com.letta.mobile.data.voice.wake

import com.letta.mobile.data.voice.WakeLanguage
import com.letta.mobile.data.voice.WakePhrase
import com.letta.mobile.data.voice.WakeWordEngineId
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.wake.assets.ReadyWakeWordAssets
import com.letta.mobile.data.voice.wake.assets.WakeWordAsset
import com.letta.mobile.data.voice.wake.assets.WakeWordAssetState
import com.letta.mobile.data.voice.wake.assets.WakeWordAssets
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

internal val FakeFormat = AudioFrameFormat()

/** A phrase hit the fake engine reports at one frame index. */
internal data class ScriptedHit(val text: String, val confidence: Float = 0.9f)

/** How the fake engine behaves. */
internal data class FakeEngineScript(
    val hits: Map<Long, ScriptedHit> = emptyMap(),
    val openFailure: WakeWordOpenFailure? = null,
    val failAtFrame: Long? = null,
    val unpronounceable: Set<String> = setOf("xq zv kt"),
)

/** A scriptable engine: hits by frame index, for accepted phrases only. */
internal class FakeWakeWordEngine(
    id: String = "fake",
    private val script: FakeEngineScript = FakeEngineScript(),
    required: List<WakeWordAsset> = emptyList(),
) : WakeWordEngine {
    val sessions = mutableListOf<FakeWakeWordSession>()

    override val descriptor = WakeWordEngineDescriptor(
        id = WakeWordEngineId(id),
        phraseKinds = setOf(WakePhraseKind.TYPED_TEXT),
        languages = setOf(WakeLanguage.English),
        maxPhrases = 2,
        format = FakeFormat,
        requiredAssets = required,
    )

    override suspend fun open(assets: ReadyWakeWordAssets): WakeWordSessionResult {
        script.openFailure?.let { return WakeWordSessionResult.Failed(it) }
        return WakeWordSessionResult.Opened(FakeWakeWordSession(descriptor, script).also { sessions += it })
    }
}

internal class FakeWakeWordSession(
    private val descriptor: WakeWordEngineDescriptor,
    private val script: FakeEngineScript,
) : WakeWordSession {
    private var accepted: List<WakePhrase> = emptyList()
    var closes = 0
        private set

    override suspend fun setPhrases(phrases: List<WakePhrase>): List<PhraseOutcome> {
        val outcomes = mutableListOf<PhraseOutcome>()
        phrases.forEach { phrase ->
            outcomes += rejection(phrase, outcomes)?.let { PhraseOutcome.Rejected(phrase, it) } ?: PhraseOutcome.Accepted(phrase)
        }
        accepted = outcomes.filterIsInstance<PhraseOutcome.Accepted>().map { it.phrase }
        return outcomes
    }

    private fun rejection(phrase: WakePhrase, earlier: List<PhraseOutcome>): PhraseRejection? = when {
        phrase.language !in descriptor.languages -> PhraseRejection.UNSUPPORTED_LANGUAGE
        phrase.text in script.unpronounceable -> PhraseRejection.UNPRONOUNCEABLE
        earlier.count { it is PhraseOutcome.Accepted } >= descriptor.maxPhrases -> PhraseRejection.TOO_MANY
        else -> null
    }

    override fun process(frame: AudioFrame): WakeWordHit? {
        if (closes > 0 || frame.samples.size != descriptor.format.samplesPerFrame) return null
        val index = frame.start.value / descriptor.format.frameSamples
        check(index != script.failAtFrame) { "engine failure" }
        val scripted = script.hits[index] ?: return null
        val phrase = accepted.firstOrNull { it.text == scripted.text } ?: return null
        return WakeWordHit(phrase, DetectionConfidence(scripted.confidence), frame.start)
    }

    override fun reset() = Unit

    override fun close() {
        closes++
    }
}

/** Frame [index] in the fake format. */
internal fun frameAt(index: Int, size: FrameSize = FrameSize.EXACT): AudioFrame =
    AudioFrame(ShortArray(FakeFormat.samplesPerFrame + size.extraSamples), SamplePosition(index.toLong() * FakeFormat.frameSamples))

/** Whether a test frame matches the fake format's size. */
internal enum class FrameSize(val extraSamples: Int) { EXACT(0), OVERSIZED(1) }

/** A frame source emitting [count] frames; [beforeFrame] runs before each; [hold] keeps it open after. */
internal class FakeFrameSource(
    private val count: Int,
    override val format: AudioFrameFormat = FakeFormat,
    private val hold: Boolean = false,
    private val beforeFrame: (Int) -> Unit = {},
) : AudioFrameSource {
    var opens = 0
        private set
    var released = false
        private set

    override fun frames(): Flow<AudioFrame> = flow {
        opens++
        try {
            repeat(count) { index ->
                beforeFrame(index)
                emit(frameAt(index))
            }
            if (hold) awaitCancellation()
        } finally {
            released = true
        }
    }
}

/** Assets in a fixed state. */
internal class FakeWakeWordAssets(initial: WakeWordAssetState = WakeWordAssetState.Missing) : WakeWordAssets {
    private val mutableState = MutableStateFlow(initial)
    var ensures = 0
        private set

    override val state: StateFlow<WakeWordAssetState> = mutableState

    override suspend fun ensure(required: List<WakeWordAsset>): WakeWordAssetState {
        ensures++
        return mutableState.value
    }

    override suspend fun remove() {
        mutableState.value = WakeWordAssetState.Missing
    }
}
