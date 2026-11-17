#!/usr/bin/env python3
"""Score normalized core-loop evidence; missing/invalid observations always fail.

This is an offline oracle, not a device driver. Times must be milliseconds in one
monotonic clock domain (the collector must supply clock alignment). Row frames
must cover the visible assistant window continuously from before terminal until
post-settle, including the cancelled partial row in S3. Never infer stored display
from model completion or spinner disappearance. See perf-gate.md.
"""
import argparse
import json
import math
from pathlib import Path

SCENARIOS = ('S1', 'S2', 'S3', 'S4')
MAX_LATENCY_MS = 3000
MAX_SPINNER_MS = 5000
MAX_FRAME_GAP_MS = 100
MIN_CORRELATION = 0.9
MAX_DIP_PERCENT = 10
FIXED_REPLY = '\n\n'.join((
    'The garden is quiet after the rain. Small drops remain on the leaves while the path slowly dries in the afternoon light.',
    'A warm cup of tea is waiting on the table. Take a moment to enjoy it before returning to the next task on your list.',
    'Everything requested is now complete. This final paragraph stays visible when the streaming reply becomes durable conversation history.',
))


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def score(evidence):
    errors = []
    def require(condition, reason):
        if not condition:
            errors.append(reason)
    require(evidence.get('scenario') in SCENARIOS, 'unknown scenario')
    require(evidence.get('client') in ('pixel2xl', 'desktop'), 'unknown client')
    terminal = evidence.get('terminalMs')
    stored = evidence.get('storedDisplayedMs')
    spinner = evidence.get('stopGoneMs')
    latency = stored - terminal if number(stored) and number(terminal) else None
    stop_latency = spinner - terminal if number(spinner) and number(terminal) else None
    require(number(latency) and 0 <= latency <= MAX_LATENCY_MS, 'stored-copy latency absent or exceeded')
    require(number(stop_latency) and 0 <= stop_latency <= MAX_SPINNER_MS, 'Stop disappearance absent or exceeded')
    expected = 'DONE' if evidence.get('scenario') == 'S2' else FIXED_REPLY
    require(isinstance(expected, str) and bool(expected) and evidence.get('visibleText') == expected,
            'visible final text differs or is missing')
    frames = evidence.get('frames', [])
    require(isinstance(frames, list) and len(frames) >= 2, 'row frames missing')
    identities = {}
    retired = set()
    previous = set()
    stamps = []
    if isinstance(frames, list):
        for frame in frames:
            if not isinstance(frame, dict) or not number(frame.get('ms')) or not isinstance(frame.get('rows'), list):
                errors.append('invalid row frame')
                continue
            stamps.append(frame['ms'])
            current = set()
            keys = []
            for row in frame['rows']:
                if not isinstance(row, dict) or not row.get('id') or not row.get('key'):
                    errors.append('row identity missing')
                    continue
                row_id, key = row['id'], row['key']
                require(isinstance(row_id, str) and isinstance(key, str), 'invalid identity type')
                if not isinstance(row_id, str) or not isinstance(key, str):
                    continue
                require(row_id not in current, 'duplicate assistant row')
                require(row_id not in retired, 'assistant row removed and re-added')
                require(row_id not in identities or identities[row_id] == key, 'assistant row key changed')
                identities[row_id] = key
                current.add(row_id)
                keys.append(key)
            require(len(keys) == len(set(keys)), 'duplicate row key')
            retired.update(previous - current)
            previous = current
    require(bool(identities), 'no assistant identity observed')
    require(not retired, 'assistant row removed from observed window')
    require(bool(stamps) and all(0 < b - a <= MAX_FRAME_GAP_MS for a, b in zip(stamps, stamps[1:])),
            'row evidence has gaps or is unordered')
    require(bool(stamps) and number(terminal) and number(stored) and stamps[0] < terminal
            and stamps[-1] >= max(terminal + MAX_SPINNER_MS, stored), 'row window does not cover settlement')
    mounts = evidence.get('lifecycle')
    require(isinstance(mounts, list) and bool(mounts), 'mount/dispose evidence missing')
    if isinstance(mounts, list):
        require(all(isinstance(event, dict) and event.get('event') == 'mount'
                    and event.get('key') in identities.values() for event in mounts), 'row disposed or invalid lifecycle event')
        require({event.get('key') for event in mounts if isinstance(event, dict)} == set(identities.values()),
                'lifecycle does not cover observed rows')
        require(len(mounts) == len(identities), 'row mounted more than once')
    pixels = evidence.get('pixels', {})
    if not isinstance(pixels, dict):
        pixels = {}
    correlation = pixels.get('minimum_template_correlation')
    dip = pixels.get('dip_percent')
    require(number(correlation) and correlation >= MIN_CORRELATION, 'pixel template missing or lost')
    require(number(dip) and dip <= MAX_DIP_PERCENT, 'reply luminance missing or dipped')
    require(number(pixels.get('frames')) and pixels['frames'] >= 2, 'pixel frames missing')
    require(evidence.get('clockAligned') is True, 'clock alignment missing')
    return dict(scenario=evidence.get('scenario'), client=evidence.get('client'),
                status='FAIL' if errors else 'PASS', storedCopyLatencyMs=latency,
                stopLatencyMs=stop_latency, minimumCorrelation=correlation if number(correlation) else None,
                dipPercent=dip if number(dip) else None,
                errors=sorted(set(errors)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    for index, path in enumerate(args.evidence):
        try:
            result = score(json.loads(path.read_text()))
        except (OSError, ValueError, TypeError, AttributeError) as error:
            result = dict(status='FAIL', errors=[f'invalid evidence: {error}'])
        (args.output / f'run-{index + 1}.json').write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
        results.append(result)
    print('| Scenario | Client | Result | Stored ms | Stop ms | Correlation | Dip % |')
    print('| --- | --- | --- | --- | --- | --- | --- |')
    for result in results:
        print('| ' + ' | '.join(str(result.get(key, 'missing')) for key in
              ('scenario', 'client', 'status', 'storedCopyLatencyMs', 'stopLatencyMs', 'minimumCorrelation', 'dipPercent')) + ' |')
    return int(any(result['status'] != 'PASS' for result in results))


if __name__ == '__main__':
    raise SystemExit(main())
