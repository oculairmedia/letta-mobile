package com.letta.mobile.data.voice.loop.machine

import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.VoiceDecision
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance

// Words the ear offers before it has decided the turn is over
// (com.letta.mobile.data.voice.engine.VoiceEarEvent.TentativeEndOfTurn) are sent at once, so the
// agent starts early, but their answer is held behind a revision gate: none of it plays until the
// turn's end of turn arrives with the same words. If the person goes on speaking, or the final
// words differ, the offered answer is dropped (its run is asked to stop, its held text is never
// spoken) and the final words are answered as a turn of their own. The gate only ever holds audio
// that has not started, so whether speech over a reply interrupts it is unchanged.

/**
 * The ear offered [utterance] early: it is answered at once, held behind the gate. Offers are
 * taken only from a plain transcript, while the loop is listening or awaiting an answer (an offer
 * over the app's own speech goes the usual way at the turn's end), and only while the microphone
 * stays open during an answer, so the turn's end that confirms the offer is still heard. A new
 * offer replaces the one before it.
 */
internal fun tentativeOffered(model: LoopModel, utterance: VoiceUtterance): Step {
    val plain = utterance.decision == VoiceDecision.Respond && utterance.immediateReply == null
    val answering = model.phase == VoicePhase.LISTENING || model.phase == VoicePhase.THINKING
    if (utterance.isEmpty || !plain || !answering || !model.config.bargeIn) return Step(model)
    val replaced = withdrawTentative(model)
    val key = TurnKey(replaced.model.turn.nextKey)
    val marked = replaced.model.withTurn { copy(tentative = key, tentativeWords = utterance.text) }
    return Turns.start(marked, utterance).after(replaced.effects)
}

/**
 * The offer is void: the person went on speaking, or the final words differ. Its answer leaves the
 * queue with whatever text it held (never spoken) and, while it may still produce text, its run is
 * asked to stop and anything more it says is dropped.
 */
internal fun withdrawTentative(model: LoopModel): Step {
    if (model.turn.tentativeWords == null) return Step(model)
    val key = model.turn.tentative
    val cleared = model.withTurn { copy(tentative = null, tentativeWords = null) }
    val reply = key?.let(cleared.turn::find) ?: return Step(cleared)
    val effects = buildList {
        if (reply.open) add(LoopEffect.RequestInterrupt(reply.key))
        addAll(skipped(SpeechSkipReason.REVISED, reply.held.size))
    }
    val dropped = cleared.withTurn {
        val rest = without(reply.key)
        if (reply.open) rest.copy(interrupting = rest.interrupting + reply.key) else rest
    }
    return Transitions.maybeFinish(dropped).after(effects)
}

/**
 * The turn that was offered early ended with [utterance] (its counters already settled): the same
 * words open the gate and the held answer plays in its turn; other words win, so the offer is
 * withdrawn and they are answered as a new turn (nothing for an empty transcript).
 */
internal fun settleTentative(model: LoopModel, utterance: VoiceUtterance): Step {
    val offered = model.turn.tentativeWords
    if (offered != null && sameWords(offered, utterance.text)) {
        val confirmed = model.withTurn { copy(tentative = null, tentativeWords = null) }.withView { copy(transcript = utterance.text) }
        return Transitions.maybeFinish(confirmed)
    }
    val withdrawn = withdrawTentative(model)
    return Turns.start(withdrawn.model, utterance).after(withdrawn.effects)
}

/**
 * Whether [a] and [b] say the same words: compared without case, punctuation or spacing, and with
 * apostrophes dropped, so a recogniser's different punctuation of the same speech still matches.
 */
internal fun sameWords(a: VoiceText, b: VoiceText): Boolean = wordsOf(a) == wordsOf(b)

private fun wordsOf(text: VoiceText): List<String> {
    val folded = buildString {
        text.value.forEach { char ->
            when {
                char.isLetterOrDigit() -> append(char.lowercaseChar())
                char == '\'' || char == '’' -> Unit
                else -> append(' ')
            }
        }
    }
    return folded.split(' ').filter { it.isNotEmpty() }
}
