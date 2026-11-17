"""Shared VFR pixel coverage oracle used by analyzer and scorer."""

from bisect import bisect_right
from .coverage_timing import CoverageInput, MAX_FRAME_GAP_MS, LIMIT, EPSILON, finite, ordered, log_continuous, log_bounds


def gap_spans(data):
    held, missing = [], []
    for start, end in zip(data.frame_times, data.frame_times[1:]):
        if end <= data.window[0] or start >= data.window[1]:
            continue
        if end - start <= LIMIT + EPSILON:
            continue
        index = bisect_right(data.draw_times, start)
        target = missing if index < len(data.draw_times) and data.draw_times[index] < end else held
        target.append([start, end])
    return held, missing


def coverage_record(data):
    times = data.frame_times
    valid = ordered(times) and all(map(finite, data.draw_times))
    valid = valid and data.draw_times == sorted(set(data.draw_times))
    held, missing = gap_spans(data) if valid else ([], [])
    continuous = log_continuous(data.log_times, data.window)
    bounds = log_bounds(times, data.window)
    reasons = coverage_reasons(valid, bounds, missing, continuous)
    return dict(
        start_seconds=times[0] if times else None,
        end_seconds=times[-1] if times else None,
        max_gap_seconds=max((b - a for a, b in zip(times, times[1:])), default=0),
        frame_times=times, draw_times=data.draw_times, log_times=data.log_times,
        held_spans=held, missing_spans=missing, log_continuous=continuous,
        complete=not reasons, reasons=reasons,
    )


def coverage_reasons(valid, bounds, missing, continuous):
    checks = (
        (valid, "pixel coverage unordered or invalid"),
        (bounds, "pixel coverage does not cover window"),
        (not missing, "draw inside pixel gap"),
        (continuous, "row log discontinuous"),
    )
    return [reason for passed, reason in checks if not passed]


def pixel_coverage_complete(pixels):
    record = pixels.get("coverage")
    settle, post = pixels.get("settle_seconds"), pixels.get("post_seconds")
    if not isinstance(record, dict) or not all(map(finite, (settle, post))):
        return False
    arrays = [record.get(key) for key in ("frame_times", "draw_times", "log_times")]
    if not all(isinstance(values, list) for values in arrays) or post < 0:
        return False
    if not all(all(map(finite, values)) for values in arrays):
        return False
    recomputed = coverage_record(CoverageInput(*arrays, (settle - 1, settle + post)))
    return recomputed == record and recomputed["complete"]


