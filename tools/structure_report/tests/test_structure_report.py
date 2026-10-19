import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('structure', Path(__file__).resolve().parents[1] / 'structure_report.py')
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class StructureTest(unittest.TestCase):
    def test_cycles_include_long_cycles_and_exclude_outgoing_tails(self):
        edges = {('a', 'b'), ('b', 'c'), ('c', 'a'), ('c', 'd'), ('x', 'a')}
        self.assertEqual(['a -> b', 'b -> c', 'c -> a'], report.cyclic_edges(edges))

    def test_dag_has_no_cycles(self):
        self.assertEqual([], report.cyclic_edges({('a', 'b'), ('b', 'c'), ('a', 'c')}))

    def test_expect_actual_names_count_once_but_distinct_modules_count_separately(self):
        records = [{'type': 'file', 'package': 'p', 'path': 'a', 'mentions': ''},
                   {'type': 'type', 'package': 'p', 'path': 'a', 'name': 'p.Type', 'kind': 'class', 'module': 'm'},
                   {'type': 'type', 'package': 'p', 'path': 'b', 'name': 'p.Type', 'kind': 'class', 'module': 'm'},
                   {'type': 'type', 'package': 'p', 'path': 'c', 'name': 'p.Type', 'kind': 'class', 'module': 'other'}]
        self.assertEqual(2, report.metrics(records)['packages'][0]['types'])

    def test_fan_in_and_out_ignore_duplicate_edges(self):
        records = [{'type': 'file', 'package': p, 'path': p, 'mentions': ''} for p in 'abc']
        records += [{'type': 'importEdge', 'from': 'a', 'to': 'b'}] * 2
        records += [{'type': 'importEdge', 'from': 'c', 'to': 'b'}]
        packages = {row['package']: row for row in report.metrics(records)['packages']}
        self.assertEqual(2, packages['b']['fanIn'])
        self.assertEqual(1, packages['a']['fanOut'])

    def test_candidate_mentions_are_conservative_and_file_scoped(self):
        records = [{'type': 'file', 'path': 'a', 'mentions': 'Unused Used'},
                   {'type': 'file', 'path': 'b', 'mentions': 'Used'},
                   {'type': 'public', 'path': 'a', 'name': 'Unused'},
                   {'type': 'public', 'path': 'a', 'name': 'Used'}]
        self.assertEqual(['Unused'], [row['name'] for row in report.declaration_candidates(records)])

    def test_mirrors_require_shared_counterpart(self):
        types = [{'name': name, 'path': name} for name in ('p.DesktopThing', 'p.WebThing', 'p.SharedThing', 'p.WebOther', 'p.FlowProbe')]
        mirrors, helpers = report.name_candidates(types)
        self.assertEqual(2, len(mirrors))
        self.assertEqual('p.FlowProbe', helpers[0]['name'])

    def baseline(self, limits=None, cycles=None):
        return {'schema': 1, 'defaultTypeLimit': 50, 'packageTypeLimits': limits or {}, 'cyclicImportEdges': cycles or []}

    def test_size_ratchet_rejects_growth_and_stale_limits(self):
        self.assertTrue(report.baseline_drift(self.baseline({'p': 70}), self.baseline({'p': 60})))
        self.assertTrue(report.baseline_drift(self.baseline({'p': 55}), self.baseline({'p': 60})))
        self.assertEqual([], report.baseline_drift(self.baseline({'p': 60}), self.baseline({'p': 60})))

    def test_equal_cycle_count_cannot_swap_signatures(self):
        errors = report.baseline_drift(self.baseline(cycles=['a -> b']), self.baseline(cycles=['b -> a']))
        self.assertEqual(2, len(errors))

    def test_invalid_default_limit_and_duplicate_cycles_rejected(self):
        changed = self.baseline()
        changed['defaultTypeLimit'] = 51
        with self.assertRaises(ValueError):
            report.validate_baseline_shape(changed)
        with self.assertRaises(ValueError):
            report.validate_baseline_shape(self.baseline(cycles=['a -> b', 'a -> b']))

    def test_missing_or_empty_export_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sources.jsonl'
            path.write_text('', encoding='utf-8')
            with self.assertRaises(ValueError):
                report.load_records(path)

    def repository(self, directory):
        root = Path(directory)
        subprocess.run(['git', 'init', '-q', str(root)], check=True)
        for key, value in [('user.name', 'Test'), ('user.email', 'test@example.invalid')]:
            subprocess.run(['git', '-C', str(root), 'config', key, value], check=True)
        return root

    def commit(self, root):
        report.git(root, 'add', '.')
        report.git(root, '-c', 'core.hooksPath=/dev/null', 'commit', '-qm', 'fixture')
        return report.git(root, 'rev-parse', 'HEAD')

    def test_baseline_cannot_authorize_its_own_growth(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.repository(directory)
            baseline = root / report.BASELINE
            baseline.parent.mkdir(parents=True)
            baseline.write_text(json.dumps(self.baseline({'p': 60}, ['a -> b'])), encoding='utf-8')
            base = self.commit(root)
            changed = self.baseline({'p': 61}, ['a -> b', 'b -> a'])
            baseline.write_text(json.dumps(changed), encoding='utf-8')
            self.assertEqual(2, len(report.baseline_growth(root, base, changed)))
            self.assertEqual([], report.baseline_growth(root, base, self.baseline({'p': 55})))

    def test_initial_baseline_cannot_hide_changed_production_code(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.repository(directory)
            source = root / 'android-compose/m/src/commonMain/kotlin/A.kt'
            source.parent.mkdir(parents=True)
            source.write_text('package p\nclass A', encoding='utf-8')
            base = self.commit(root)
            source.write_text('package p\nclass B', encoding='utf-8')
            self.assertTrue(report.baseline_growth(root, base, self.baseline()))

    def test_html_escapes_source_names(self):
        data = report.metrics([{'type': 'file', 'package': '<script>', 'path': 'a', 'mentions': ''}])
        data['sourceCommit'] = 'abc'
        document = report.render(data, 'FAILED <unsafe>')
        self.assertNotIn('<script>', document)
        self.assertIn('&lt;script&gt;', document)
        self.assertIn('&lt;unsafe&gt;', document)


if __name__ == '__main__':
    unittest.main()
