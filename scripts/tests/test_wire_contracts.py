"""Negative cases for the non-Gradle wire-contract gate."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("wire_contracts", ROOT / "scripts/ci/check-wire-contracts.py")


class WireContractsTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_baseline_is_sorted_unique_and_unpadded(self):
        for text in ("z.method\na.method\n", "a.method\na.method\n", " a.method\n"):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "sorted, unique|whitespace"):
                self.module.read_baseline(text)
        self.assertEqual({"a.method", "z.method"}, self.module.read_baseline("# header\na.method\nz.method\n"))

    def test_baseline_growth_fails(self):
        with self.assertRaisesRegex(ValueError, "baseline grew.*"):
            self.module.require_no_growth({"a.method", "new.method"}, {"a.method"})
        self.module.require_no_growth({"a.method"}, {"a.method", "old.method"})
        self.module.require_no_growth({"a.method"}, None)

    def pair(self):
        spec = self.root / self.module.SPECS / "health.check/status-v1.md"
        fixture = self.root / self.module.FIXTURES / "health.check/status-v1.json"
        spec.parent.mkdir(parents=True)
        fixture.parent.mkdir(parents=True)
        spec.write_text("# health.check v1\n")
        fixture.write_text('{"method":"health.check","version":1,"handler":"handler.kt","cases":[{"name":"case","context":{},"host_state":{},"params":{},"expected_response":{}}]}')
        (self.root / "handler.kt").write_text("// handler\n")
        return spec, fixture

    def test_orphans_fail(self):
        spec, fixture = self.pair()
        spec.unlink()
        with self.assertRaisesRegex(ValueError, "without spec"):
            self.module.contracts(self.root)
        spec.write_text("# spec\n")
        fixture.unlink()
        with self.assertRaisesRegex(ValueError, "without fixture"):
            self.module.contracts(self.root)

    def test_empty_cases_do_not_count_as_coverage(self):
        _, fixture = self.pair()
        fixture.write_text('{"method":"health.check","version":1,"handler":"handler.kt","cases":[]}')
        with self.assertRaisesRegex(ValueError, "non-empty cases"):
            self.module.contracts(self.root)

    def test_fixture_document_must_be_an_object(self):
        _, fixture = self.pair()
        fixture.write_text("[]")
        with self.assertRaisesRegex(ValueError, "JSON object"):
            self.module.contracts(self.root)

    def test_method_must_match_path(self):
        _, fixture = self.pair()
        fixture.write_text(fixture.read_text().replace('"method":"health.check"', '"method":"different.method"'))
        with self.assertRaisesRegex(ValueError, "method/path mismatch"):
            self.module.contracts(self.root)

    def test_version_must_match_path(self):
        _, fixture = self.pair()
        fixture.write_text(fixture.read_text().replace('"version":1', '"version":2'))
        with self.assertRaisesRegex(ValueError, "version/path mismatch"):
            self.module.contracts(self.root)

    def test_index_is_deterministic_and_detects_staleness(self):
        self.pair()
        rows = self.module.contracts(self.root)
        text = self.module.render_index(rows)
        self.assertIn("health.check", text)
        self.assertIn("status-v1.json", text)
        self.module.check_index(self.root, rows, write=True)
        self.module.check_index(self.root, rows, write=False)
        (self.root / self.module.INDEX).write_text("stale\n")
        with self.assertRaisesRegex(ValueError, "Stale wire-contract index"):
            self.module.check_index(self.root, rows, write=False)

    def test_cli_enforces_diff_base_and_rejects_growth(self):
        from contextlib import redirect_stderr
        from io import StringIO
        from unittest.mock import patch
        self.pair()
        baseline = self.root / self.module.BASELINE
        baseline.parent.mkdir(parents=True)
        baseline.write_text("a.method\nnew.method\n")
        stderr = StringIO()
        with patch.object(self.module, "previous_baseline", return_value={"a.method"}) as prior, redirect_stderr(stderr):
            result = self.module.main(["--root", str(self.root), "--diff-base", "HEAD", "--check"])
        self.assertEqual(1, result)
        self.assertIn("baseline grew", stderr.getvalue())
        self.assertIn("new.method", stderr.getvalue())
        prior.assert_called_once_with(self.root, "HEAD")

    def test_diff_base_reads_previous_baseline_and_rejects_growth(self):
        from unittest.mock import patch
        results = [subprocess.CompletedProcess([], 0, "a" * 40),
                   subprocess.CompletedProcess([], 0, self.module.BASELINE + "\n"),
                   subprocess.CompletedProcess([], 0, "a.method\n")]
        with patch("subprocess.run", side_effect=results) as run:
            previous = self.module.previous_baseline(self.root, "HEAD")
        self.assertIn("show", run.call_args.args[0])
        with self.assertRaisesRegex(ValueError, "baseline grew"):
            self.module.require_no_growth({"a.method", "new.method"}, previous)


if __name__ == "__main__":
    unittest.main()
