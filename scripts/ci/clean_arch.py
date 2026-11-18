"""Source-level architecture ratchets. Run from the repository root; no Gradle required."""
import argparse
import re
from collections import Counter
from pathlib import Path
import subprocess

from clean_arch_rules import findings
from kotlin_source_scan import production_files

BASELINE = Path('scripts/ci/parity/clean-arch-baseline.txt')
ALLOWLIST = 'scripts/ci/platform-ui-allowlist.txt'
MODULES = ['sharedLogic', 'sharedUI', 'desktop', 'web', 'app', 'feature-*', 'designsystem']


def review_changes(base):
    if not re.fullmatch(r'[0-9a-f]{40}', base):
        raise ValueError('expected full base commit SHA')
    changes = subprocess.check_output(['git', 'diff', '--unified=0', base, '--', ALLOWLIST], text=True)
    for line in changes.splitlines():
        if line.startswith(('+android-compose/', '-android-compose/')) and 'legacy-pending(' in line:
            print('Legacy UI exception delta:', line)
    diff = subprocess.check_output(['git', 'diff', '--name-only', base, '--', ':(glob)**/DesktopChatShellHelpers.kt'], text=True)
    # Kotlin function boundaries are not always recognized by Git. Flag the known
    # policy owner conservatively rather than claiming the change is a violation.
    if diff:
        print('Review desktop stack/rail selection: keep membership and archive policy shared.')


def inventory(files):
    counts = Counter()
    locations = {}
    for file in files:
        text = file.read_text()
        for rule, offset in findings(file.as_posix(), text):
            key = f'{rule}:{file}'
            counts[key] += 1
            locations[key] = text.count('\n', 0, offset) + 1
    return counts, locations


def compare(counts, locations, caps):
    errors = 0
    for key in sorted(counts.keys() | caps.keys()):
        if counts[key] != caps.get(key, 0):
            remedy = 'fix new violation' if counts[key] > caps.get(key, 0) else 'lower stale baseline'
            print(f'{key}:{locations.get(key, 1)}: {remedy} ({counts[key]} vs {caps.get(key, 0)})')
            errors += 1
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', help='git revision for advisory PR deltas')
    args = parser.parse_args()
    files = production_files(Path('.'), MODULES)
    counts, locations = inventory(files)
    rows = (line.split() for line in BASELINE.read_text().splitlines() if line and not line.startswith('#'))
    errors = compare(counts, locations, {key: int(n) for key, n in rows})
    if args.base:
        review_changes(args.base)
    print(f'Clean architecture: {len(files)} production files; {sum(counts.values())} existing findings; {errors} differences')
    return bool(errors) or not files


if __name__ == '__main__':
    raise SystemExit(main())
