"""Question sets for the Letta Mobile System 1 layer.

Laya evaluates *typed questions* over a state blob in a single non-autoregressive
forward pass. Three question types exist:

  choice -> {"type": "choice", "instructions": ..., "criteria": {option: gloss}}
  score  -> {"type": "score",  "instructions": ..., "criteria": [level0, level1, ...]}
  noul   -> {"type": "noul",   "instructions": ...}   (calibrated true probability)

The `guard`, `route` and `triage` sets are Laya's own shipped presets: they are
trained-for prompts, so we do NOT paraphrase them. Each preset addresses the
state through a specific key (`prompt`, `request`, `message` respectively), which
is why :func:`unified_state` aliases the same user text under all three.

The `interaction` set is ours. It is the reason this layer exists: deciding
whether a half-typed composer buffer deserves an LLM turn is a per-keystroke
question, and a System 2 call per keystroke is neither affordable nor fast
enough.
"""

from typing import Any, Dict, List, Optional

from laya import guard_questions, router_questions, triage_questions

# Laya preset state keys, kept next to the presets that depend on them.
GUARD_STATE_KEY = "prompt"
ROUTE_STATE_KEY = "request"
TRIAGE_STATE_KEY = "message"

INTERACTION_MODES = {
    "respond_now": "the user is done and wants a substantive answer",
    "wait_for_more": "the user is mid-thought and more input is coming",
    "ask_clarification": "the request is under-specified and needs one question back",
    "acknowledge_only": "a greeting, thanks or aside that needs no real work",
}


def interaction_questions() -> Dict[str, Dict[str, Any]]:
    """Turn-taking questions for the interactive composer.

    SCOPE: semantic questions only. "Is the draft finished?" and "has it changed
    enough?" are deliberately NOT here — see `calibration_probe.py`. Measured on
    the stock `convaiinnovations/laya` checkpoint, completeness framings produce
    overlapping distributions (best min/max separation +0.007 across 6 complete
    and 6 truncated drafts), against +0.87 for the shipped jailbreak preset on
    the same harness. Those two questions are answered deterministically in the
    Kotlin reflex tier (`System1ReflexGate`) instead, which is both exact and
    free.

    `expects_response` uses the best-separating framing found by the probe
    (mean gap +0.38 vs +0.11 for the obvious phrasing). `interaction_mode`
    is advisory: read it together with its confidence, never as a hard gate.
    """
    return {
        "expects_response": {
            "type": "noul",
            "instructions": (
                "Does `draft` ask the assistant to actually do something or answer "
                "something, rather than just acknowledging?"
            ),
            "criteria": {
                "true": "it requests an answer or an action",
                "false": "it is only thanks, acknowledgement or small talk",
            },
        },
        "interaction_mode": {
            "type": "choice",
            "instructions": "How should the assistant react to `draft` right now?",
            "criteria": dict(INTERACTION_MODES),
        },
        "answer_latency_tolerance": {
            "type": "score",
            "instructions": "How long is the user willing to wait for a reply to `draft`?",
            "criteria": [
                "instant: expects an immediate, reflexive answer",
                "short: a few seconds of thinking is fine",
                "patient: a slow, thorough answer is welcome",
            ],
        },
    }


def unified_state(
    text: str,
    conversation: Optional[List[Any]] = None,
    previous_draft: Optional[str] = None,
    context: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build one state blob addressable by every question set.

    Laya serializes a dict state to JSON and appends it to the token sequence, so
    the preset keys must be present verbatim. The same `text` is aliased under
    all of them rather than reworded per set, which keeps a combined
    `/v1/system1/evaluate` a single forward pass over a single state.
    """
    state: Dict[str, Any] = {
        GUARD_STATE_KEY: text,
        ROUTE_STATE_KEY: text,
        TRIAGE_STATE_KEY: text,
        "draft": text,
    }
    state["previous_draft"] = previous_draft if previous_draft is not None else ""
    if conversation:
        state["conversation"] = conversation
    if context:
        # Caller context must not shadow the keys the presets address.
        for key, value in context.items():
            if key not in state:
                state[key] = value
    return state


def question_set(name: str) -> Dict[str, Dict[str, Any]]:
    """Resolve a named question set."""
    sets = {
        "guard": guard_questions,
        "route": router_questions,
        "triage": triage_questions,
        "interaction": interaction_questions,
    }
    if name not in sets:
        raise KeyError(name)
    return sets[name]()


def combined_questions(sets: List[str]) -> Dict[str, Dict[str, Any]]:
    """Namespace-prefixed union of several sets, for one batched forward pass.

    Prefixing (`guard.jailbreak`) keeps ids unique across sets that would
    otherwise collide, and lets the response be split back apart per set.
    """
    merged: Dict[str, Dict[str, Any]] = {}
    for name in sets:
        for qid, qdef in question_set(name).items():
            merged[f"{name}.{qid}"] = qdef
    return merged
