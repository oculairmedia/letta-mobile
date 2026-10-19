#!/usr/bin/env python3
"""Render parsed Kotlin structure and enforce monotonic size/cycle limits."""
import argparse
from collections import defaultdict
import hashlib
import html
import json
import re
from pathlib import Path
import subprocess

BASELINE = 'android-compose/architecture-tests/structure-baseline.json'
DEFAULT_TYPE_LIMIT = 50


def load_records(path):
    records = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    if not records or not any(row['type'] == 'file' for row in records):
        raise ValueError('No parsed production files; generate the structure export first')
    return records


def cyclic_edges(edges):
    """An edge participates in a directed cycle iff its target can reach its source."""
    adjacency = defaultdict(set)
    for source, target in edges:
        adjacency[source].add(target)
    reachable = {}
    for node in sorted(adjacency):
        seen, pending = set(), [node]
        while pending:
            current = pending.pop()
            for target in adjacency[current] - seen:
                seen.add(target)
                pending.append(target)
        reachable[node] = seen
    return sorted(f'{source} -> {target}' for source, target in edges if source in reachable.get(target, set()))


def declaration_candidates(records):
    mentions = defaultdict(set)
    declarations = [row for row in records if row['type'] == 'public']
    for row in records:
        if row['type'] == 'file':
            for name in row['mentions'].split():
                mentions[name].add(row['path'])
    return [row for row in declarations if not (mentions[row['name']] - {row['path']})]


def grouped(records, key):
    result = defaultdict(list)
    for row in records:
        result[row[key]].append(row)
    return result


def mirrored_names(names):
    result = []
    for name, declarations in sorted(names.items()):
        prefix = next((prefix for prefix in ('Desktop', 'Web') if name.startswith(prefix)), None)
        if prefix is None:
            continue
        shared = 'Shared' + name.removeprefix(prefix)
        if shared in names:
            paths = sorted({row['path'] for row in declarations + names[shared]})
            result.append({'platform': name, 'shared': shared, 'paths': paths})
    return result


def name_candidates(types):
    names = defaultdict(list)
    for row in types:
        names[row['name'].rsplit('.', 1)[-1]].append(row)
    helpers = [row for name, declarations in names.items() if helper_name(name) for row in declarations]
    return mirrored_names(names), helpers


def helper_name(name):
    return any(part in name for part in ('TestTags', 'Probe', 'Smoke', 'Sample'))


def package_type_counts(types):
    # Actual/expect declarations and platform implementations count once per module/name.
    return {package: len({(row['module'], row['kind'], row['name']) for row in declarations})
            for package, declarations in grouped(types, 'package').items()}


def package_metrics(files, types, edges):
    counts = package_type_counts(types)
    fan_in, fan_out = defaultdict(set), defaultdict(set)
    for source, target in edges:
        fan_in[target].add(source)
        fan_out[source].add(target)
    return [{'package': package, 'types': counts.get(package, 0),
             'files': len({row['path'] for row in rows}),
             'fanIn': len(fan_in[package]), 'fanOut': len(fan_out[package])}
            for package, rows in sorted(grouped(files, 'package').items())]


def metrics(records):
    kinds = grouped(records, 'type')
    edges = {(row['from'], row['to']) for row in kinds['importEdge']}
    mirrors, helpers = name_candidates(kinds['type'])
    return {'packages': package_metrics(kinds['file'], kinds['type'], edges),
            'cyclicImportEdges': cyclic_edges(edges),
            'zeroOtherFileMentionCandidates': declaration_candidates(records),
            'mirroredNameCandidates': mirrors, 'helperNameCandidates': helpers}


def expected_baseline(report):
    return {'schema': 1, 'defaultTypeLimit': DEFAULT_TYPE_LIMIT,
            'packageTypeLimits': {row['package']: row['types'] for row in report['packages']
                                  if row['types'] > DEFAULT_TYPE_LIMIT},
            'cyclicImportEdges': report['cyclicImportEdges']}


def validate_type_limits(limits):
    if any(type(value) is not int or value <= DEFAULT_TYPE_LIMIT for value in limits.values()):
        raise ValueError('Type limits must be integers above the default')


def validate_baseline_shape(baseline):
    if (baseline.get('schema'), baseline.get('defaultTypeLimit')) != (1, DEFAULT_TYPE_LIMIT):
        raise ValueError('Unsupported baseline schema or changed default type limit')
    validate_type_limits(baseline['packageTypeLimits'])
    cycles = baseline['cyclicImportEdges']
    if cycles != sorted(set(cycles)):
        raise ValueError('Cyclic import edges must be sorted and unique')


def size_drift(actual, baseline):
    problems = []
    for package in sorted(actual.keys() | baseline.keys()):
        current = actual.get(package, DEFAULT_TYPE_LIMIT)
        allowed = baseline.get(package, DEFAULT_TYPE_LIMIT)
        if current > allowed:
            problems.append(f'{package}: {current} types exceeds {allowed}; split the package')
        elif current < allowed:
            problems.append(f'{package}: lower/remove stale type baseline {allowed} to {current}')
    return problems


def cycle_drift(actual, baseline):
    new, stale = set(actual) - set(baseline), set(baseline) - set(actual)
    return ([f'New cyclic import edge: {edge}' for edge in sorted(new)] +
            [f'Remove stale cyclic edge baseline: {edge}' for edge in sorted(stale)])


def baseline_drift(actual, baseline):
    validate_baseline_shape(baseline)
    return (size_drift(actual['packageTypeLimits'], baseline['packageTypeLimits']) +
            cycle_drift(actual['cyclicImportEdges'], baseline['cyclicImportEdges']))


def git(root, *arguments):
    return subprocess.check_output(['git', '-C', str(root), *arguments], text=True, encoding='utf-8').strip()


def immutable_commit(root, base_ref):
    if not re.fullmatch(r'(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})', base_ref):
        raise ValueError('Base revision must be a full immutable commit SHA')
    return git(root, 'rev-parse', '--verify', '--end-of-options', f'{base_ref}^{{commit}}')


def baseline_growth(root, base_ref, baseline):
    commit = immutable_commit(root, base_ref)
    tree = git(root, 'ls-tree', '--name-only', commit, '--', BASELINE)
    if not tree:
        # Bootstrap only when the measured production sources are unchanged from the base.
        changes = git(root, 'diff', '--name-only', commit, '--', 'android-compose').splitlines()
        production = [path for path in changes if '/src/' in path and
                      not path.split('/src/', 1)[1].split('/')[0].lower().endswith('test')]
        return ['Initial baseline requires unchanged production sources: ' + path for path in production]
    previous = json.loads(git(root, 'show', f'{commit}:{BASELINE}'))
    validate_baseline_shape(previous)
    errors = [f'Baseline increase forbidden: {package}' for package, value in baseline['packageTypeLimits'].items()
              if value > previous['packageTypeLimits'].get(package, DEFAULT_TYPE_LIMIT)]
    errors.extend(f'New baseline cyclic edge forbidden: {edge}' for edge in
                  sorted(set(baseline['cyclicImportEdges']) - set(previous['cyclicImportEdges'])))
    return errors


def table(title, rows):
    rows = list(rows)
    body = ''.join('<tr>' + ''.join('<td>' + html.escape(str(cell)) + '</td>' for cell in row) + '</tr>' for row in rows)
    return f'<details><summary>{html.escape(title)} ({len(rows)})</summary><table>{body}</table></details>'


def render(report, boundary_text):
    sections = [table('Package / types / files / import fan-in / fan-out',
                      ([row[k] for k in ('package', 'types', 'files', 'fanIn', 'fanOut')] for row in report['packages'])),
                table('Cyclic import edges', ([edge] for edge in report['cyclicImportEdges'])),
                table('Public top-level declarations with zero other-file name mentions (candidates)',
                      ([row['name'], row['path']] for row in report['zeroOtherFileMentionCandidates'])),
                table('Mirrored names (candidates)',
                      ([row['platform'], row['shared'], ', '.join(row['paths'])] for row in report['mirroredNameCandidates'])),
                table('Test/probe/sample names in production (candidates)',
                      ([row['name'], row['path']] for row in report['helperNameCandidates']))]
    return '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Letta Mobile structure report</title><style>body{font:16px system-ui;margin:2rem;max-width:100rem}td{padding:.35rem;border-bottom:1px solid #ccc;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse}summary{cursor:pointer;font-weight:bold;margin:1rem 0}pre{white-space:pre-wrap}</style>
<h1>Letta Mobile structure report</h1><p><a href="../api/">API reference</a> Â· <a href="report.json">Machine-readable report</a></p>
<p>Generated from parsed production Kotlin, using the architecture tests' production scope. Named classes, interfaces and objects include nested types, exclude local/anonymous types, and deduplicate actual/expect names per module. Type aliases and Java declarations are outside this metric. Import edges use the existing longest-package-prefix resolver; they are dependency evidence, not resolved calls.</p>
<p>Candidate tables are advisory. Zero other-file mentions is a conservative textual search including comments/strings; same-file calls, aliasing, reflection, generated code and external plugin consumers require review. It is not proof that an API is unused. Mirrored and helper names do not prove duplication or test-only behavior.</p>''' + '<p>Source commit: <code>' + html.escape(report['sourceCommit']) + '</code></p>' + ''.join(sections) + '<h2>Existing module-boundary gate</h2><pre>' + html.escape(boundary_text) + '</pre></html>'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--base-ref', required=True, help='Immutable PR base SHA (or main parent on push)')
    parser.add_argument('--write-baseline', action='store_true', help='Write observed debt; does not authorize growth')
    args = parser.parse_args()
    root = args.root.resolve()
    output = root / 'android-compose/build/reports/structure'
    records = load_records(output / 'sources.jsonl')
    report = metrics(records)
    report['sourceCommit'] = git(root, 'rev-parse', 'HEAD')
    report['sourceDigest'] = hashlib.sha256((output / 'sources.jsonl').read_bytes()).hexdigest()
    boundary_text = (root / 'android-compose/build/reports/architecture/boundaries.txt').read_text(encoding='utf-8')
    report['moduleBoundaryReport'] = boundary_text
    (output / 'report.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    (output / 'index.html').write_text(render(report, boundary_text), encoding='utf-8')
    actual = expected_baseline(report)
    baseline_path = root / BASELINE
    if args.write_baseline:
        baseline_path.write_text(json.dumps(actual, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    baseline = json.loads(baseline_path.read_text(encoding='utf-8'))
    errors = baseline_drift(actual, baseline) + baseline_growth(root, args.base_ref, baseline)
    if errors:
        parser.exit(1, '\n'.join(errors) + '\n')
    print(f"Structure contract: {len(report['packages'])} packages checked; report at {output}")


if __name__ == '__main__':
    main()
