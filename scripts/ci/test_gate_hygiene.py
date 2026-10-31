"""End-to-end fixtures for the PR tree and web ceiling gates."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

CI = Path(__file__).resolve().parent
TREE_GATE = CI / "tree-hygiene.py"
BUDGET_GATE = CI / "web-bundle-raise-guard.py"
BASELINE = "scripts/ci/tree-hygiene-baseline.txt"
BUDGET = "android-compose/web/bundle-budget.json"
APPROVAL = "Budget raise approved-by: oculairmedia"


class GateFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "fixture@example.com")
        self.git("config", "user.name", "Fixture")
        self.write(BASELINE, "dot .legacy\nmd plan/old.md\nscript old/legacy.py\n")
        self.write(BUDGET, json.dumps({"groups": {"app": {"ceiling_gzip_bytes": 100}}}))
        self.write(".legacy/existing.txt", "old")
        self.write("plan/old.md", "old")
        self.write("old/legacy.py", "old")
        self.git("add", ".")
        self.git("commit", "-qm", "base")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True, text=True)

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def run_gate(self, gate, body="", event=None):
        env = os.environ.copy()
        env.pop("GITHUB_EVENT_PATH", None)
        env["PR_BODY"] = body
        if event:
            env["GITHUB_EVENT_PATH"] = str(event)
        return subprocess.run(["python3", str(gate), "--root", str(self.root), "--diff-base", "HEAD"], env=env, capture_output=True, text=True)

    def test_dot_directories_and_file_level_rules(self):
        self.write(".legacy/new.txt", "ok")
        self.write(".github/new.txt", "ok")
        self.assertEqual(self.run_gate(TREE_GATE).returncode, 0)
        self.write(".new/new.txt", "bad")
        result = self.run_gate(TREE_GATE)
        self.assertEqual(result.returncode, 1)
        self.assertIn("new top-level dot-directory: .new/new.txt", result.stderr)

    def test_markdown_locations_and_existing_exception(self):
        for path in ("docs/new.md", "android-compose/app/Module.md", "android-compose/app/README.md", "README.md"):
            self.write(path, "ok")
        self.assertEqual(self.run_gate(TREE_GATE).returncode, 0)
        self.write("plan/new.md", "bad")
        result = self.run_gate(TREE_GATE)
        self.assertEqual(result.returncode, 1)
        self.assertIn("misplaced Markdown: plan/new.md", result.stderr)

    def test_scripts_require_a_real_reference(self):
        self.write("scripts/ok.py", "ok")
        self.write("tools/ok.sh", "ok")
        self.write("misc/task.py", "print('task.py')")
        self.assertIn("unreferenced script", self.run_gate(TREE_GATE).stderr)
        self.write(".github/workflows/build.yml", "run: python3 misc/task.py\n")
        self.assertEqual(self.run_gate(TREE_GATE).returncode, 0)
        self.write("misc/other.sh", "python3 task.py\n")
        self.write("Makefile", "run: ; bash misc/other.sh\n")
        self.assertEqual(self.run_gate(TREE_GATE).returncode, 0)
        self.write("misc/bad.py", "")
        self.write("build.gradle.kts", 'val x = "elsewhere/bad.py"')
        self.assertIn("misc/bad.py", self.run_gate(TREE_GATE).stderr)
        self.write("build.gradle.kts", 'val x = "${rootDir}/misc/bad.py"')
        self.assertEqual(self.run_gate(TREE_GATE).returncode, 0)

    def test_baseline_is_shrink_only_and_cannot_be_deleted(self):
        self.write(BASELINE, "dot .legacy\n")
        self.assertEqual(self.run_gate(TREE_GATE).returncode, 0)
        self.write(BASELINE, "dot .legacy\ndot .new\n")
        self.assertIn("baseline entry added: dot .new", self.run_gate(TREE_GATE).stderr)
        (self.root / BASELINE).unlink()
        self.assertEqual(self.run_gate(TREE_GATE).returncode, 2)

    def test_renames_are_checked_as_additions(self):
        self.git("mv", "plan/old.md", "plan/new.md")
        self.assertIn("misplaced Markdown: plan/new.md", self.run_gate(TREE_GATE).stderr)

    def budget(self, value, name="app"):
        self.write(BUDGET, json.dumps({"groups": {name: {"ceiling_gzip_bytes": value}}}))

    def test_budget_lower_equal_raise_and_approval(self):
        for value in (99, 100):
            self.budget(value)
            self.assertEqual(self.run_gate(BUDGET_GATE).returncode, 0)
        self.budget(101)
        self.assertEqual(self.run_gate(BUDGET_GATE).returncode, 1)
        for body in ("prefix " + APPROVAL, APPROVAL + " extra", "Budget raise approved-by: someone"):
            self.assertEqual(self.run_gate(BUDGET_GATE, body).returncode, 1)
        self.assertEqual(self.run_gate(BUDGET_GATE, "text\r\n  " + APPROVAL + "\r\n").returncode, 0)

    def test_new_or_renamed_budget_group_requires_approval(self):
        self.budget(100, "renamed")
        self.assertEqual(self.run_gate(BUDGET_GATE).returncode, 1)
        self.assertEqual(self.run_gate(BUDGET_GATE, APPROVAL).returncode, 0)

    def test_event_body_fallback_and_bad_budget(self):
        self.budget(101)
        event = self.root / "event.json"
        event.write_text(json.dumps({"pull_request": {"body": APPROVAL}}))
        self.assertEqual(self.run_gate(BUDGET_GATE, event=event).returncode, 0)
        self.budget("101")
        self.assertEqual(self.run_gate(BUDGET_GATE, APPROVAL).returncode, 2)

    def test_invalid_base_fails_closed(self):
        result = subprocess.run(["python3", str(TREE_GATE), "--root", str(self.root), "--diff-base", "missing"], capture_output=True)
        self.assertEqual(result.returncode, 2)
        result = subprocess.run(["python3", str(BUDGET_GATE), "--root", str(self.root), "--diff-base", "missing"], capture_output=True)
        self.assertEqual(result.returncode, 2)

    def test_invalid_refs_are_rejected_cleanly_by_both_gates(self):
        for gate in (TREE_GATE, BUDGET_GATE):
            for ref, message in (("--help", "invalid diff base"), ("missing-ref", "does not resolve to a commit")):
                with self.subTest(gate=gate.name, ref=ref):
                    result = subprocess.run(
                        ["python3", str(gate), "--root", str(self.root), f"--diff-base={ref}"],
                        capture_output=True, text=True,
                    )
                    self.assertEqual(result.returncode, 2)
                    self.assertIn(message, result.stderr)
                    self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
