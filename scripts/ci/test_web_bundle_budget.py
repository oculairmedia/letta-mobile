"""Tests for web-bundle-budget.py: run with python3 -m unittest discover -s scripts/ci -p 'test_web_bundle_budget.py'."""

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("web_bundle_budget", Path(__file__).with_name("web-bundle-budget.py"))
budget_gate = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = budget_gate
_SPEC.loader.exec_module(budget_gate)


class WebBundleBudgetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.dist = self.root / "dist"
        (self.dist / "iroh").mkdir(parents=True)
        # Incompressible bytes, so gzip cannot shrink them below the ceilings used here.
        (self.dist / "abc123.wasm").write_bytes(os.urandom(4000))
        (self.dist / "iroh" / "letta_iroh_wasm_bg.wasm").write_bytes(os.urandom(2000))
        (self.dist / "index.html").write_text("<html></html>")

    def tearDown(self):
        self.tmp.cleanup()

    def run_gate(self, groups):
        budget = self.root / "budget.json"
        budget.write_text(json.dumps({"groups": groups}))
        summary = self.root / "summary.md"
        code = budget_gate.main([str(self.dist), "--budget", str(budget), "--summary-md", str(summary)])
        return code, summary.read_text()

    def test_passes_when_every_group_fits_its_ceiling(self):
        code, summary = self.run_gate({
            "app": {"include": ["*.wasm"], "ceiling_gzip_bytes": 10000},
            "iroh": {"include": ["iroh/*.wasm"], "ceiling_gzip_bytes": 10000},
        })
        self.assertEqual(code, 0)
        self.assertIn("| app | 1 |", summary)

    def test_fails_when_a_group_grows_past_its_ceiling(self):
        code, summary = self.run_gate({"iroh": {"include": ["iroh/*.wasm"], "ceiling_gzip_bytes": 100}})
        self.assertEqual(code, 1)
        self.assertIn("over budget", summary)

    def test_root_pattern_does_not_count_nested_files(self):
        sizes = budget_gate.measure(self.dist, {"groups": {"app": {"include": ["*.wasm"], "ceiling_gzip_bytes": 1}}})
        self.assertEqual(sizes[0].files, ("abc123.wasm",))

    def test_a_group_that_matches_nothing_fails_closed(self):
        with self.assertRaises(SystemExit):
            self.run_gate({"rive": {"include": ["rive/rive.wasm"], "ceiling_gzip_bytes": 10000}})


if __name__ == "__main__":
    unittest.main()
