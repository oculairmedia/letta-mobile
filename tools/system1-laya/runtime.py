"""Model runtime + decision policy for the System 1 service.

Separated from the FastAPI wiring in `server.py` so the policy (thresholds,
disposition, report shaping) is unit-testable without standing up a server or
loading a model.
"""

import os
import threading
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

HARM_LEVELS = ["none", "minor", "serious", "severe"]
DIFFICULTY_LEVELS = ["trivial", "easy", "moderate", "hard"]
FRUSTRATION_LEVELS = ["calm", "concerned", "annoyed", "angry"]
LATENCY_TOLERANCE_LEVELS = ["instant", "short", "patient"]

# See `interaction_report` for why this is not 0.5.
EXPECTS_RESPONSE_CUTOFF = 0.3

# Dispositions, ordered by severity. The Kotlin `System1Disposition` enum mirrors
# these names; changing one without the other breaks the client contract.
BLOCK = "BLOCK"
WARN = "WARN"
FAST_TRACK = "FAST_TRACK"
ALLOW = "ALLOW"


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Thresholds:
    """Decision cut-offs applied to Laya's calibrated probabilities.

    Defaults are deliberately conservative on the blocking side: a false BLOCK is
    a user-visible refusal, while a false ALLOW still meets System 2's own safety
    handling downstream.
    """

    jailbreak_block: float = 0.85
    injection_block: float = 0.85
    harm_block: float = 2.5
    jailbreak_warn: float = 0.5
    injection_warn: float = 0.5
    sensitive_data_warn: float = 0.6
    harm_warn: float = 1.5
    fast_track_difficulty: float = 1.0

    @classmethod
    def from_env(cls) -> "Thresholds":
        return cls(
            jailbreak_block=_env_float("SYSTEM1_JAILBREAK_BLOCK", cls.jailbreak_block),
            injection_block=_env_float("SYSTEM1_INJECTION_BLOCK", cls.injection_block),
            harm_block=_env_float("SYSTEM1_HARM_BLOCK", cls.harm_block),
            jailbreak_warn=_env_float("SYSTEM1_JAILBREAK_WARN", cls.jailbreak_warn),
            injection_warn=_env_float("SYSTEM1_INJECTION_WARN", cls.injection_warn),
            sensitive_data_warn=_env_float("SYSTEM1_SENSITIVE_WARN", cls.sensitive_data_warn),
            harm_warn=_env_float("SYSTEM1_HARM_WARN", cls.harm_warn),
            fast_track_difficulty=_env_float(
                "SYSTEM1_FAST_TRACK_DIFFICULTY", cls.fast_track_difficulty
            ),
        )

    def merged_with(self, overrides: Optional[Dict[str, float]]) -> "Thresholds":
        if not overrides:
            return self
        known = {k: v for k, v in overrides.items() if v is not None and hasattr(self, k)}
        if not known:
            return self
        return Thresholds(**{**self.__dict__, **known})


def _level(score: float, levels: List[str]) -> str:
    """Nearest discrete label for a Laya expected-value score."""
    idx = int(round(score))
    return levels[max(0, min(len(levels) - 1, idx))]


def _noul(answers: Dict[str, Any], qid: str) -> float:
    return float(answers.get(qid, {}).get("noul", 0.0))


def _score(answers: Dict[str, Any], qid: str) -> float:
    return float(answers.get(qid, {}).get("score", 0.0))


def _choice(answers: Dict[str, Any], qid: str) -> str:
    return str(answers.get(qid, {}).get("choice", "other"))


def _confidence(answers: Dict[str, Any], qid: str) -> float:
    return float(answers.get(qid, {}).get("confidence", 0.0))


def guard_report(answers: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
    p = prefix
    harm = _score(answers, p + "harm_severity")
    return {
        "jailbreak_prob": _noul(answers, p + "jailbreak"),
        "injection_prob": _noul(answers, p + "prompt_injection"),
        "sensitive_data_prob": _noul(answers, p + "sensitive_data"),
        "harm_severity": harm,
        "harm_severity_label": _level(harm, HARM_LEVELS),
        "topic": _choice(answers, p + "topic"),
        "topic_confidence": _confidence(answers, p + "topic"),
    }


def route_report(answers: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
    p = prefix
    difficulty = _score(answers, p + "difficulty")
    needs_tools = _noul(answers, p + "needs_tools")
    is_sensitive = _noul(answers, p + "is_sensitive")
    return {
        "difficulty": difficulty,
        "difficulty_label": _level(difficulty, DIFFICULTY_LEVELS),
        "domain": _choice(answers, p + "domain"),
        "domain_confidence": _confidence(answers, p + "domain"),
        "needs_tools": needs_tools >= 0.5,
        "needs_tools_prob": needs_tools,
        "is_sensitive": is_sensitive >= 0.5,
        "is_sensitive_prob": is_sensitive,
    }


def triage_report(answers: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
    p = prefix
    frustration = _score(answers, p + "frustration")
    urgent = _noul(answers, p + "is_urgent")
    return {
        "intent": _choice(answers, p + "intent"),
        "intent_confidence": _confidence(answers, p + "intent"),
        "is_urgent": urgent >= 0.5,
        "is_urgent_prob": urgent,
        "frustration": frustration,
        "frustration_label": _level(frustration, FRUSTRATION_LEVELS),
        "churn_risk": _noul(answers, p + "churn_risk"),
    }


def interaction_report(answers: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
    """Semantic turn-taking signals.

    Completeness and change-detection are NOT here: the reflex tier answers
    those deterministically. See `questions.interaction_questions`.

    `expects_response` uses a 0.3 cut-off rather than 0.5. The probe's best
    framing separates by mean but sits low in absolute terms (request mean 0.50,
    acknowledgement mean 0.12), so 0.5 would read most genuine requests as
    "no reply wanted". Callers that need a hard gate should use the probability.
    """
    p = prefix
    expects = _noul(answers, p + "expects_response")
    tolerance = _score(answers, p + "answer_latency_tolerance")
    return {
        "expects_response": expects >= EXPECTS_RESPONSE_CUTOFF,
        "expects_response_prob": expects,
        "interaction_mode": _choice(answers, p + "interaction_mode"),
        "interaction_mode_confidence": _confidence(answers, p + "interaction_mode"),
        "latency_tolerance": tolerance,
        "latency_tolerance_label": _level(tolerance, LATENCY_TOLERANCE_LEVELS),
    }


def decide(
    guard: Dict[str, Any],
    route: Dict[str, Any],
    thresholds: Thresholds,
) -> Dict[str, str]:
    """Collapse guard + route signals into a single disposition and a reason.

    Safety dominates: a BLOCK or WARN from the guard set wins over any routing
    opinion, so a cheap-looking prompt can never fast-track past a jailbreak
    signal.
    """
    safety = _safety_verdict(guard, thresholds)
    if safety is not None:
        return safety

    if _can_fast_track(route, thresholds):
        return {
            "disposition": FAST_TRACK,
            "reason": "trivial request with no tool or sensitivity signal",
        }
    return {"disposition": ALLOW, "reason": "no System 1 objection"}


def _safety_verdict(guard: Dict[str, Any], thresholds: Thresholds) -> Optional[Dict[str, str]]:
    if guard["jailbreak_prob"] >= thresholds.jailbreak_block:
        return {"disposition": BLOCK, "reason": "jailbreak probability above block threshold"}
    if guard["injection_prob"] >= thresholds.injection_block:
        return {
            "disposition": BLOCK,
            "reason": "prompt-injection probability above block threshold",
        }
    if guard["harm_severity"] >= thresholds.harm_block:
        return {"disposition": BLOCK, "reason": "harm severity above block threshold"}

    if guard["jailbreak_prob"] >= thresholds.jailbreak_warn:
        return {"disposition": WARN, "reason": "elevated jailbreak probability"}
    if guard["injection_prob"] >= thresholds.injection_warn:
        return {"disposition": WARN, "reason": "elevated prompt-injection probability"}
    if guard["sensitive_data_prob"] >= thresholds.sensitive_data_warn:
        return {"disposition": WARN, "reason": "input appears to carry sensitive data"}
    if guard["harm_severity"] >= thresholds.harm_warn:
        return {"disposition": WARN, "reason": "elevated harm severity"}

    return None


def _can_fast_track(route: Dict[str, Any], thresholds: Thresholds) -> bool:
    if route["difficulty"] > thresholds.fast_track_difficulty:
        return False
    return not route["needs_tools"] and not route["is_sensitive"]


class System1Runtime:
    """Thread-safe wrapper around a loaded Laya agent.

    Inference is serialized with a lock. Concurrent forward passes on one CUDA
    context give no throughput win at this model size and make tail latency
    unpredictable, which is the one thing a System 1 layer cannot afford.
    """

    def __init__(
        self, model_id: str = "convaiinnovations/laya", device: Optional[str] = None
    ) -> None:
        import laya

        self.model_id = model_id
        started = time.perf_counter()
        self.agent = laya.load(model_id, device=device)
        self.load_seconds = round(time.perf_counter() - started, 3)
        self.device = str(self.agent.device)
        self.dtype = str(self.agent.dtype)
        self.started_at = time.time()
        self.request_count = 0
        self._lock = threading.Lock()

    def evaluate(self, state: Any, questions: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
        """One batched forward pass over every question. Returns Laya's raw result."""
        started = time.perf_counter()
        with self._lock:
            result = self.agent.system_one(state, questions)
        result["latency_ms"] = round((time.perf_counter() - started) * 1000, 2)
        self.request_count += 1
        return result

    def health(self) -> Dict[str, Any]:
        info: Dict[str, Any] = {
            "status": "ok",
            "model": self.model_id,
            "device": self.device,
            "dtype": self.dtype,
            "load_seconds": self.load_seconds,
            "uptime_seconds": round(time.time() - self.started_at, 1),
            "requests_served": self.request_count,
        }
        try:
            import torch

            if self.device.startswith("cuda") and torch.cuda.is_available():
                info["gpu_memory_allocated_mb"] = round(
                    torch.cuda.memory_allocated() / (1024 * 1024), 1
                )
                info["gpu_name"] = torch.cuda.get_device_name(0)
        except Exception:  # pragma: no cover - diagnostics must never fail health
            pass
        return info
