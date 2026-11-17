#!/usr/bin/env python3
"""Score normalized core-loop evidence; missing/invalid observations always fail.

This is an offline oracle, not a device driver. Times must be aligned millisecond
timestamps (the collector must supply clock alignment). Row frames
must cover the visible assistant window continuously from before terminal until
post-settle, including the cancelled partial row in S3. Never infer stored display
from model completion or spinner disappearance. See perf-gate.md.
"""

from dataclasses import dataclass, field

import math
from .coverage import MAX_FRAME_GAP_MS, pixel_coverage_complete

SCENARIOS = ("S1", "S2", "S3", "S4")
MAX_LATENCY_MS = 3000
MAX_SPINNER_MS = 5000
MIN_CORRELATION = 0.9
MAX_DIP_PERCENT = 10
FIXED_REPLY = "\n\n".join(
    (
        "The garden is quiet after the rain. Small drops remain on the leaves while the path slowly dries in the afternoon light.",
        "A warm cup of tea is waiting on the table. Take a moment to enjoy it before returning to the next task on your list.",
        "Everything requested is now complete. This final paragraph stays visible when the streaming reply becomes durable conversation history.",
    )
)


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def check_held(details, require):
    held, expected = details.get("heldToolMs"), details.get("heldToolExpectedMs")
    require(number(expected) and 7000 <= expected <= 110000, "held-tool fixture duration missing or invalid")
    require(
        number(held) and number(expected) and held >= expected - 1000,
        "current held tool start/end missing or hold too short",
    )


def check_cancelled(details, require):
    delta = details.get("cancelToResendMs")
    require(details.get("cancelled") is True, "cancelled terminal not observed")
    require(
        details.get("cancelledPartialIncomplete") is True, "cancelled partial reply was not evidenced before completion"
    )
    require(number(delta) and 0 <= delta <= 1500, "follow-up did not reach the tight cancellation window")


def check_outage(details, require):
    outage = details.get("networkDownMs")
    require(number(outage) and outage >= 5000, "network outage timing missing or too short")
    require(details.get("networkOverlappedLive") is True, "network drop did not overlap observed live reply")


def check_scenario(scenario, details, require):
    checks = {"S2": check_held, "S3": check_cancelled, "S4": check_outage}
    if scenario in checks:
        checks[scenario](details, require)


def has_row_identity(row):
    return isinstance(row, dict) and bool(row.get("id")) and bool(row.get("key"))


@dataclass
class RowWindow:
    identities: dict = field(default_factory=dict)
    retired: set = field(default_factory=set)
    rekeyed: set = field(default_factory=set)
    previous: set = field(default_factory=set)
    stamps: list = field(default_factory=list)
    duplicates: int = 0


def check_row(row, window, current, require):
    row_id, key = row["id"], row["key"]
    valid = isinstance(row_id, str) and isinstance(key, str)
    require(valid, "invalid identity type")
    if not valid:
        return None
    require(row_id not in current, "duplicate assistant row")
    require(row_id not in window.retired, "assistant row removed and re-added")
    same_key = row_id not in window.identities or window.identities[row_id] == key
    if not same_key:
        window.rekeyed.add(row_id)
    require(same_key, "assistant row key changed")
    window.identities[row_id] = key
    current.add(row_id)
    return key


def check_rows(rows, window, require):
    current, keys = set(), []
    for row in rows:
        if not has_row_identity(row):
            require(False, "row identity missing")
            continue
        key = check_row(row, window, current, require)
        if key is not None:
            keys.append(key)
    return current, keys


def valid_frame(frame):
    if not isinstance(frame, dict):
        return False
    return number(frame.get("ms")) and isinstance(frame.get("rows"), list)


def check_frame(frame, window, require):
    if not valid_frame(frame):
        require(False, "invalid row frame")
        return
    window.stamps.append(frame["ms"])
    current, keys = check_rows(frame["rows"], window, require)
    unique_keys = len(keys) == len(set(keys))
    if not unique_keys or len(current) != len(keys):
        window.duplicates += 1
    require(unique_keys, "duplicate row key")
    window.retired.update(window.previous - current)
    window.previous = current


def covers_settlement(stamps, terminal, stored):
    if not stamps:
        return False
    if not settlement_times_valid(terminal, stored):
        return False
    return stamps[0] < terminal and stamps[-1] >= max(terminal + MAX_SPINNER_MS, stored)


def check_frames(context):
    frames = context.evidence.get("frames", [])
    require = context.require
    require(isinstance(frames, list) and len(frames) >= 2, "row frames missing")
    window = RowWindow()
    if isinstance(frames, list):
        for frame in frames:
            check_frame(frame, window, require)
    require(bool(window.identities), "no assistant identity observed")
    require(not window.retired, "assistant row removed from observed window")
    require(
        bool(window.stamps) and all(0 < b - a <= MAX_FRAME_GAP_MS for a, b in zip(window.stamps, window.stamps[1:])),
        "row evidence has gaps or is unordered",
    )
    require(covers_settlement(window.stamps, context.terminal, context.stored), "row window does not cover settlement")
    context.window = window


def check_lifecycle(evidence, identities, require):
    mounts = evidence.get("lifecycle")
    require(isinstance(mounts, list) and bool(mounts), "mount/dispose evidence missing")
    if isinstance(mounts, list):
        require(
            all(
                isinstance(event, dict)
                and event.get("event") in ("mount", "present")
                and event.get("key") in identities.values()
                for event in mounts
            ),
            "row disposed or invalid lifecycle event",
        )
        require(
            {event.get("key") for event in mounts if isinstance(event, dict)} == set(identities.values()),
            "lifecycle does not cover observed rows",
        )
        require(len(mounts) == len(identities), "row mounted more than once")
    return mounts


def check_pixels(evidence, require):
    pixels = evidence.get("pixels", {})
    if not isinstance(pixels, dict):
        pixels = {}
    require(pixel_coverage_complete(pixels), "pixel coverage missing or incomplete")
    if "partial" in pixels:
        require(
            pixel_coverage_complete(pixels["partial"]),
            "partial pixel coverage missing or incomplete",
        )
    correlation = pixels.get("minimum_template_correlation")
    dip = pixels.get("dip_percent")
    require(
        number(correlation) and MIN_CORRELATION <= correlation <= 1,
        "pixel template missing or lost",
    )
    require(number(dip) and dip <= MAX_DIP_PERCENT, "reply luminance missing or dipped")
    require(number(pixels.get("frames")) and pixels["frames"] >= 2, "pixel frames missing")
    require(evidence.get("clockAligned") is True, "clock alignment missing")
    return correlation, dip


def difference(left, right):
    return left - right if number(left) and number(right) else None


def in_limit(value, limit):
    return number(value) and 0 <= value <= limit


@dataclass
class ScoreContext:
    evidence: dict
    errors: list = field(default_factory=list)

    def __post_init__(self):
        e = self.evidence
        self.scenario = e.get("scenario")
        self.details = e.get("scenarioEvidence", {})
        if not isinstance(self.details, dict):
            self.details = {}
        self.terminal, self.stored = e.get("terminalMs"), e.get("storedDisplayedMs")
        self.latency = difference(self.stored, self.terminal)
        self.stop_latency = difference(e.get("stopGoneMs"), self.terminal)
        self.expected = "DONE" if self.scenario == "S2" else FIXED_REPLY

    def require(self, condition, reason):
        if not condition:
            self.errors.append(reason)


def check_metadata(context):
    e, require = context.evidence, context.require
    require(context.scenario in SCENARIOS, "unknown scenario")
    require(e.get("client") in ("pixel2xl", "desktop"), "unknown client")
    require(context.details.get("terminalReason") == "end_turn", "natural final terminal missing")
    require(
        context.details.get("newStoredTextObserved") is True, "fixed reply not evidenced in a newly drawn stored row"
    )
    inputs = context.details.get("inputCount")
    require(type(inputs) is int and inputs >= (2 if context.scenario == "S3" else 1), "scenario input evidence missing")
    check_scenario(context.scenario, context.details, require)
    require(e.get("initialUnclassifiedRows") == 0, "initial row roles missing from evidence")
    require(e.get("duplicateUserRowSamples") == 0, "user duplicate evidence missing or duplicates observed")


def check_completion(context):
    e, require = context.evidence, context.require
    require(in_limit(context.latency, MAX_LATENCY_MS), "stored-copy latency absent or exceeded")
    require(in_limit(context.stop_latency, MAX_SPINNER_MS), "Stop disappearance absent or exceeded")
    require(e.get("finalStopPresent") is False, "final Stop state missing or still visible")
    require(
        isinstance(context.expected, str) and bool(context.expected) and e.get("visibleText") == context.expected,
        "visible final text differs or is missing",
    )


def check_rendering(context):
    mounts = check_lifecycle(context.evidence, context.window.identities, context.require)
    context.disposes = (
        sum(isinstance(e, dict) and e.get("event") == "dispose" for e in mounts) if isinstance(mounts, list) else None
    )
    context.correlation, context.dip = check_pixels(context.evidence, context.require)


def identity_stable(context):
    window = context.window
    return bool(window.identities) and not window.retired and not window.rekeyed and context.disposes == 0


def nullable_number(value):
    return value if number(value) else None


def score_result(context):
    e, window = context.evidence, context.window
    return dict(
        scenario=context.scenario if context.scenario in SCENARIOS else None,
        client=e.get("client") if e.get("client") in ("pixel2xl", "desktop") else None,
        rowIdentityStable=identity_stable(context),
        rowDisposeCount=context.disposes,
        duplicateUserRowSamples=e.get("duplicateUserRowSamples"),
        initialUnclassifiedRows=e.get("initialUnclassifiedRows"),
        rekeyedRowCount=len(window.rekeyed),
        duplicateRowFrames=window.duplicates,
        finalTextCorrect=isinstance(context.expected, str) and e.get("visibleText") == context.expected,
        spinnerClearedWithinLimit=in_limit(context.stop_latency, MAX_SPINNER_MS) and e.get("finalStopPresent") is False,
        newStoredTextObserved=context.details.get("newStoredTextObserved"),
        status="FAIL" if context.errors else "PASS",
        storedCopyLatencyMs=context.latency,
        stopLatencyMs=context.stop_latency,
        minimumCorrelation=nullable_number(context.correlation),
        dipPercent=nullable_number(context.dip),
        errors=sorted(set(context.errors)),
    )


CRITERIA = (check_metadata, check_completion, check_frames, check_rendering)


def score(evidence):
    context = ScoreContext(evidence)
    for check in CRITERIA:
        check(context)
    return score_result(context)


def settlement_times_valid(terminal, stored):
    return number(terminal) and number(stored)
