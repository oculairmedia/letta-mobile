"""Source-level architecture ratchets. Run from the repository root; no Gradle required."""
import argparse
from collections import Counter
from pathlib import Path
import re
import subprocess

from kotlin_source_scan import blank_literals, production_files, tokens

BASELINE = Path('scripts/ci/parity/clean-arch-baseline.txt')
ALLOWLIST = 'scripts/ci/platform-ui-allowlist.txt'
MODULES = ['sharedLogic', 'sharedUI', 'desktop', 'web', 'app', 'feature-*', 'designsystem']
STATE = re.compile(r'\bandroidx\.compose\.runtime\.(?:\*|(?:snapshots\.)?(?:Snapshot\w*|(?:mutable|derived)\w*State\w*|State|MutableState|remember)\b)')


def ui_findings(text):
    for token in tokens(text):
        if token.kind == 'string' and text[token.start:token.end] in ('"agent_task"', '"""agent_task"""'):
            yield 'task-wire-tag', token.start
    if 'AppServerBackgroundProcess' in text:
        code = blank_literals(text, frozenset({'comment', 'char', 'string'}))
        for match in re.finditer(r'\bprocess\s*\??\.\s*type\b', code):
            yield 'raw-process-type', match.start()


def findings(path, text):
    if '/sharedLogic/' in path:
        if not any(part in path.lower() for part in ('/repository/', '/transport/', 'reducer')):
            return
        code = blank_literals(text, frozenset({'comment', 'char', 'string'}))
        for match in STATE.finditer(code):
            yield 'compose-state', match.start()
    else:
        yield from ui_findings(text)


def git(*args):
    return subprocess.check_output(['git', *args], text=True)


def review_changes(base):
    changes = git('diff', '--unified=0', base, '--', ALLOWLIST)
    for line in changes.splitlines():
        if line.startswith(('+android-compose/', '-android-compose/')) and 'legacy-pending(' in line:
            print('Legacy UI exception delta:', line)
    diff = git('diff', '--function-context', base, '--', ':(glob)**/DesktopChatShellHelpers.kt')
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
