"""Shared VFR pixel coverage oracle. All timestamps are video-relative seconds."""

from dataclasses import dataclass
import math

MAX_FRAME_GAP_MS = 100
LIMIT = MAX_FRAME_GAP_MS / 1000
EPSILON = 1e-9


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def ordered(times):
    return bool(times) and all(map(finite, times)) and all(b > a for a, b in zip(times, times[1:]))


@dataclass
class CoverageInput:
    frame_times: list
    draw_times: list
    log_times: list
    window: tuple


def log_continuous(times, window):
    start, end = window
    if not ordered(times):
        return False
    relevant = [t for t in times if start - LIMIT <= t <= end + LIMIT]
    return log_bounds(relevant, window) and all(b - a <= LIMIT + EPSILON for a, b in zip(relevant, relevant[1:]))


def log_bounds(times, window):
    if not times:
        return False
    return times[0] <= window[0] + LIMIT and times[-1] >= window[1]
