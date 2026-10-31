"""Detekt ratchet tests: python3 -m unittest discover -s scripts/ci -p 'test_baseline_ratchet_guard.py' -v."""

import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch
from xml.sax.saxutils import escape

_SPEC = importlib.util.spec_from_file_location("baseline_ratchet_guard", Path(__file__).with_name("baseline-ratchet-guard.py"))
guard = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(guard)

BASE_SHA = "a" * 40
BASELINE = "android-compose/example/detekt-baseline.xml"
NEW_BASELINE = "android-compose/new-module/detekt-baseline.xml"
CONFIG = "scripts/ci/ratchet-baselines.txt"
ORIGINAL_ID = "LongMethod:Example.kt$fun original()"
ADDED_ID = "LongMethod:Example.kt$fun added()"


def baseline_xml(*ids: str, suppressed: tuple[str, ...] = ()) -> str:
    def section(name: str, values: tuple[str, ...]) -> str:
        findings = "\n".join(f"    <ID>{escape(value)}</ID>" for value in values)
        return f"  <{name}>\n{findings}\n  </{name}>"

    return '<?xml version="1.0" ?>\n<SmellBaseline>\n' + "\n".join((
        section("ManuallySuppressedIssues", suppressed), section("CurrentIssues", ids),
    )) + "\n</SmellBaseline>\n"


class DetektRatchetGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.base_files = {
            CONFIG: f"detekt {BASELINE}\n",
            BASELINE: baseline_xml(ORIGINAL_ID),
        }
        for path, text in self.base_files.items():
            self.write(path, text)

    def write(self, path: str, text: str):
        current = self.root / path
        current.parent.mkdir(parents=True, exist_ok=True)
        current.write_text(text, encoding="utf-8")

    def run_guard(self) -> tuple[int, str]:
        output = io.StringIO()
        # Model committed files separately from the working tree, without committing fixtures.
        with patch.object(guard, "resolve_commit", return_value=BASE_SHA), patch.object(
            guard, "git_show", side_effect=lambda root, sha, path: self.base_files.get(path),
        ), redirect_stdout(output), redirect_stderr(output):
            status = guard.main(["--diff-base", BASE_SHA, "--root", str(self.root)])
        return status, output.getvalue()

    def test_unchanged_passes(self):
        status, output = self.run_guard()
        self.assertEqual(0, status, output)

    def test_removed_ids_pass(self):
        self.write(BASELINE, baseline_xml())
        status, output = self.run_guard()
        self.assertEqual(0, status, output)

    def test_added_id_fails_with_fix(self):
        self.write(BASELINE, baseline_xml(ORIGINAL_ID, ADDED_ID))
        status, output = self.run_guard()
        self.assertEqual(1, status, output)
        self.assertIn(f"{BASELINE}: detekt ID added: {ADDED_ID}", output)
        self.assertIn("Fix the detekt finding instead; never add a baseline ID", output)
        self.assertIn("there is no override", output)

    def test_replacing_an_id_at_the_same_count_fails(self):
        self.write(BASELINE, baseline_xml(ADDED_ID))
        status, output = self.run_guard()
        self.assertEqual(1, status, output)
        self.assertIn(f"detekt ID added: {ADDED_ID}", output)

    def test_xml_formatting_order_and_escaping_do_not_change_ids(self):
        escaped_id = "Rule:Example.kt$fun compare(a: List<String>) = a.size < 2 && a.isEmpty()"
        self.base_files[BASELINE] = baseline_xml(ORIGINAL_ID, escaped_id)
        self.write(BASELINE, baseline_xml(escaped_id, ORIGINAL_ID).replace("\n", ""))
        status, output = self.run_guard()
        self.assertEqual(0, status, output)

    def test_added_manually_suppressed_id_fails(self):
        self.write(BASELINE, baseline_xml(ORIGINAL_ID, suppressed=(ADDED_ID,)))
        status, output = self.run_guard()
        self.assertEqual(1, status, output)
        self.assertIn(f"detekt ID added: {ADDED_ID}", output)

    def test_removed_manually_suppressed_id_passes(self):
        self.base_files[BASELINE] = baseline_xml(ORIGINAL_ID, suppressed=(ADDED_ID,))
        status, output = self.run_guard()
        self.assertEqual(0, status, output)

    def test_first_listing_of_existing_baseline_still_rejects_growth(self):
        self.base_files[CONFIG] = ""
        self.write(BASELINE, baseline_xml(ORIGINAL_ID, ADDED_ID))
        status, output = self.run_guard()
        self.assertEqual(1, status, output)
        self.assertIn(f"detekt ID added: {ADDED_ID}", output)

    def test_first_listing_of_unchanged_existing_baseline_passes(self):
        self.base_files[CONFIG] = ""
        status, output = self.run_guard()
        self.assertEqual(0, status, output)

    def test_new_baseline_must_have_no_ids_even_in_a_new_module(self):
        for path in (BASELINE, NEW_BASELINE):
            with self.subTest(path=path):
                self.base_files.pop(path, None)
                self.write(CONFIG, f"detekt {BASELINE}\ndetekt {NEW_BASELINE}\n")
                self.write(BASELINE, baseline_xml())
                self.write(NEW_BASELINE, baseline_xml())
                status, output = self.run_guard()
                self.assertEqual(0, status, output)
                self.write(path, baseline_xml(ADDED_ID))
                status, output = self.run_guard()
                self.assertEqual(1, status, output)
                self.assertIn(f"{path}: detekt ID added: {ADDED_ID}", output)
                self.assertIn("new baseline files must contain no IDs, even in new modules", output)

    def test_unlisted_baseline_fails(self):
        self.write(NEW_BASELINE, baseline_xml(ADDED_ID))
        status, output = self.run_guard()
        self.assertEqual(1, status, output)
        self.assertIn(f"{NEW_BASELINE}: detekt baseline must be listed as 'detekt {NEW_BASELINE}'", output)

    def test_non_detekt_kind_cannot_bypass_coverage(self):
        self.base_files[CONFIG] = ""
        self.write(CONFIG, f"set {BASELINE}\n")
        status, output = self.run_guard()
        self.assertEqual(1, status, output)
        self.assertIn("detekt baseline must be listed as", output)

    def test_malformed_xml_fails_with_a_flat_error(self):
        self.write(BASELINE, "<SmellBaseline><ID>unclosed")
        status, output = self.run_guard()
        self.assertEqual(2, status, output)
        self.assertIn(f"{BASELINE}: malformed detekt baseline XML:", output)
        self.assertNotIn("Traceback", output)


if __name__ == "__main__":
    unittest.main()
