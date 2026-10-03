#!/usr/bin/env python3
"""Policy tests for the System 1 decision layer.

These exercise report shaping and disposition only, against synthetic Laya
answer blobs, so they run in milliseconds with no model, no GPU and no network.
Live-model behaviour is covered by `test_service.py`.

    python tools/system1-laya/test_runtime.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from questions import combined_questions, unified_state  # noqa: E402
from runtime import (  # noqa: E402
    ALLOW,
    BLOCK,
    FAST_TRACK,
    WARN,
    Thresholds,
    decide,
    guard_report,
    interaction_report,
    route_report,
)


def _noul(p):
    return {"type": "noul", "noul": p, "confidence": max(p, 1 - p)}


def _score(v, n=4):
    return {"type": "score", "score": v, "probabilities": {str(i): 1.0 / n for i in range(n)}}


def _choice(c):
    return {"type": "choice", "choice": c, "probabilities": {c: 1.0}, "confidence": 1.0}


def _guard(jailbreak=0.0, injection=0.0, sensitive=0.0, harm=0.0):
    return guard_report(
        {
            "jailbreak": _noul(jailbreak),
            "prompt_injection": _noul(injection),
            "sensitive_data": _noul(sensitive),
            "harm_severity": _score(harm),
            "topic": _choice("coding"),
        }
    )


def _route(difficulty=0.0, domain="chitchat", needs_tools=0.0, sensitive=0.0):
    return route_report(
        {
            "difficulty": _score(difficulty),
            "domain": _choice(domain),
            "needs_tools": _noul(needs_tools),
            "is_sensitive": _noul(sensitive),
        }
    )


CHECKS = []


def check(fn):
    CHECKS.append(fn)
    return fn


@check
def test_clean_trivial_request_fast_tracks():
    verdict = decide(_guard(), _route(difficulty=0.4), Thresholds())
    assert verdict["disposition"] == FAST_TRACK, verdict


@check
def test_hard_request_is_allowed_not_fast_tracked():
    verdict = decide(_guard(), _route(difficulty=2.8, domain="code"), Thresholds())
    assert verdict["disposition"] == ALLOW, verdict


@check
def test_tool_need_blocks_fast_track():
    verdict = decide(_guard(), _route(difficulty=0.2, needs_tools=0.9), Thresholds())
    assert verdict["disposition"] == ALLOW, verdict


@check
def test_sensitivity_blocks_fast_track():
    verdict = decide(_guard(), _route(difficulty=0.2, sensitive=0.9), Thresholds())
    assert verdict["disposition"] == ALLOW, verdict


@check
def test_high_jailbreak_blocks():
    verdict = decide(_guard(jailbreak=0.97), _route(), Thresholds())
    assert verdict["disposition"] == BLOCK, verdict


@check
def test_high_injection_blocks():
    verdict = decide(_guard(injection=0.9), _route(), Thresholds())
    assert verdict["disposition"] == BLOCK, verdict


@check
def test_severe_harm_blocks():
    verdict = decide(_guard(harm=2.9), _route(), Thresholds())
    assert verdict["disposition"] == BLOCK, verdict


@check
def test_moderate_jailbreak_warns():
    verdict = decide(_guard(jailbreak=0.6), _route(), Thresholds())
    assert verdict["disposition"] == WARN, verdict


@check
def test_sensitive_data_warns():
    verdict = decide(_guard(sensitive=0.8), _route(), Thresholds())
    assert verdict["disposition"] == WARN, verdict


@check
def test_safety_dominates_fast_track():
    """A trivial-looking prompt must never fast-track past a jailbreak signal."""
    verdict = decide(_guard(jailbreak=0.9), _route(difficulty=0.1), Thresholds())
    assert verdict["disposition"] == BLOCK, verdict


@check
def test_threshold_overrides_apply():
    lenient = Thresholds().merged_with({"jailbreak_block": 0.99, "jailbreak_warn": 0.99})
    verdict = decide(_guard(jailbreak=0.9), _route(difficulty=2.0), lenient)
    assert verdict["disposition"] == ALLOW, verdict


@check
def test_unknown_threshold_override_is_ignored():
    merged = Thresholds().merged_with({"not_a_threshold": 1.0})
    assert merged == Thresholds()


@check
def test_score_labels_round_to_nearest_level():
    assert _guard(harm=0.0)["harm_severity_label"] == "none"
    assert _guard(harm=1.4)["harm_severity_label"] == "minor"
    assert _guard(harm=2.6)["harm_severity_label"] == "severe"
    assert _route(difficulty=3.9)["difficulty_label"] == "hard"


@check
def test_missing_answers_degrade_to_neutral():
    """A truncated answer blob must not raise; it reads as all-clear."""
    report = guard_report({})
    assert report["jailbreak_prob"] == 0.0
    assert decide(report, route_report({}), Thresholds())["disposition"] == FAST_TRACK


@check
def test_interaction_report_thresholds_booleans():
    report = interaction_report(
        {
            "expects_response": _noul(0.12),
            "interaction_mode": _choice("wait_for_more"),
            "answer_latency_tolerance": _score(1.0, 3),
        }
    )
    assert report["expects_response"] is False
    assert report["interaction_mode"] == "wait_for_more"
    assert report["latency_tolerance_label"] == "short"


@check
def test_expects_response_uses_the_low_cutoff():
    """0.35 is a request, not an acknowledgement: 0.5 would misread most asks."""
    assert interaction_report({"expects_response": _noul(0.35)})["expects_response"] is True
    assert interaction_report({"expects_response": _noul(0.2)})["expects_response"] is False


@check
def test_interaction_set_omits_completeness_questions():
    """Completeness belongs to the reflex tier; Laya does not separate it."""
    from questions import interaction_questions

    qids = set(interaction_questions())
    assert "is_complete" not in qids
    assert "is_substantive_change" not in qids


@check
def test_unified_state_carries_every_preset_key():
    state = unified_state("hello", previous_draft="hell")
    for key in ("prompt", "request", "message", "draft", "previous_draft"):
        assert key in state, key
    assert state["prompt"] == "hello"


@check
def test_context_cannot_shadow_preset_keys():
    state = unified_state("hello", context={"prompt": "evil", "tenant": "acme"})
    assert state["prompt"] == "hello"
    assert state["tenant"] == "acme"


@check
def test_combined_questions_are_namespaced_and_unique():
    merged = combined_questions(["guard", "route", "triage", "interaction"])
    assert "guard.jailbreak" in merged
    assert "interaction.expects_response" in merged
    assert len(merged) == 5 + 4 + 5 + 3


def main() -> int:
    failures = []
    for fn in CHECKS:
        try:
            fn()
            print(f"  PASS {fn.__name__}")
        except AssertionError as exc:
            failures.append((fn.__name__, exc))
            print(f"  FAIL {fn.__name__}: {exc}")
    print(f"\n{len(CHECKS) - len(failures)}/{len(CHECKS)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
