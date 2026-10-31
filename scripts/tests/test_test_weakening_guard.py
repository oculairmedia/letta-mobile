"""Fixture snapshots and diffs for the test-weakening gate."""
import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

GUARD_PATH = Path(__file__).resolve().parents[1] / "ci" / "test-weakening-guard.py"
SPEC = importlib.util.spec_from_file_location("test_weakening_guard", GUARD_PATH)
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)

OLD_PATH = "module/src/commonTest/kotlin/ExampleTest.kt"
NEW_PATH = "module/src/commonTest/kotlin/MovedTest.kt"
PY_PATH = "scripts/tests/test_example.py"
BASE = "class ExampleTest {\n    @Test fun works() {\n        assertEquals(1, 1)\n        verify(mock)\n    }\n}\n"
REASON = "Test change reason: Replaced coverage with a stronger integration test."


class FixtureDiffTest(unittest.TestCase):
    def check_diff(self, before, after):
        paths = sorted(before.keys() | after.keys())
        entries = []
        for path in paths:
            if before.get(path) == after.get(path):
                continue
            status = "M" if path in before and path in after else "D" if path in before else "A"
            entries.extend((status, path))
        diff = ("\0".join(entries) + "\0").encode()

        def fake_git(root, *args):
            if args[0] == "rev-parse":
                # Real git prints the full commit SHA; the guard rejects anything else.
                return b"0123456789abcdef0123456789abcdef01234567\n"
            if args[0] == "merge-base":
                return b"base\n"
            if args[0] == "diff":
                return diff
            if args[0] == "show":
                revision, path = args[1].split(":", 1)
                return (before if revision == "base" else after)[path].encode()
            self.fail(f"unexpected git command: {args}")

        with patch.object(guard, "git", side_effect=fake_git):
            return guard.check(Path("/fixture"), "origin/main")

    def test_unchanged_passes(self):
        self.assertEqual([], self.check_diff({OLD_PATH: BASE}, {OLD_PATH: BASE}))

    def test_added_tests_pass(self):
        after = BASE.replace("    @Test", "    @Test fun added() { assertTrue(true) }\n    @Test")
        self.assertEqual([], self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after}))
        self.assertEqual([], self.check_diff({}, {OLD_PATH: after}))

    def test_removed_or_renamed_test_fails(self):
        for after in (BASE.replace("@Test", ""), BASE.replace("works", "renamed"), ""):
            with self.subTest(after=after):
                problems = self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after} if after else {})
                self.assertTrue(any("removed or renamed: works" in item for item in problems), problems)

    def test_fewer_assertions_fail(self):
        problems = self.check_diff({OLD_PATH: BASE}, {OLD_PATH: BASE.replace("        verify(mock)\n", "")})
        self.assertIn("ExampleTest: assertion calls decreased: 2 -> 1", problems)

    def test_added_disable_fails(self):
        for marker in ("@Ignore", "@Disabled", '@Suppress("unused")'):
            with self.subTest(marker=marker):
                after = BASE.replace("@Test", marker + "\n    @Test")
                problems = self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after})
                self.assertTrue(any("test disable added" in item for item in problems), problems)
        after = BASE.replace("verify(mock)", "verify(mock)\n        suite.skip { }")
        self.assertTrue(any("test disable added" in item for item in self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after})))

    def test_non_test_suppression_does_not_fail(self):
        after = BASE.replace("    @Test", '    @Suppress("unused")\n    fun helper() {}\n    @Test')
        self.assertEqual([], self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after}))

    def test_class_disable_with_arguments_fails(self):
        after = BASE.replace("class ExampleTest", '@Ignore("reason")\nclass ExampleTest')
        self.assertTrue(any("test disable added" in item for item in self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after})))

    def test_reason_overrides_each_failure(self):
        variants = (BASE.replace("@Test", ""), BASE.replace("verify(mock)", ""), BASE.replace("@Test", "@Ignore\n    @Test"))
        for after in variants:
            with self.subTest(after=after):
                problems = self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after})
                self.assertTrue(problems)
                with patch.object(guard, "check", return_value=problems), patch.object(guard, "pr_body", return_value=REASON), patch.object(sys, "argv", ["guard", "--diff-base", "base"]):
                    self.assertEqual(0, guard.main())
        self.assertFalse(guard.valid_reason("Test change reason: short"))
        self.assertFalse(guard.valid_reason("Test change reason: " + " " * 30))
        self.assertFalse(guard.valid_reason("prefix Test change reason: this reason is sufficiently long"))

    def test_failure_output_and_exit_code(self):
        after = BASE.replace("@Test", "@Ignore\n    @Test").replace("works", "renamed").replace("verify(mock)", "")
        problems = self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after})
        output = io.StringIO()
        with patch.object(guard, "check", return_value=problems), patch.object(guard, "pr_body", return_value=""), patch.object(sys, "argv", ["guard", "--diff-base", "base"]), contextlib.redirect_stdout(output):
            self.assertEqual(1, guard.main())
        self.assertEqual(output.getvalue(), (
            "::error title=Test weakening::3 potential test weakening(s) detected\n"
            "test-weakening-guard: ExampleTest: @Test/test function removed or renamed: works (1)\n"
            "test-weakening-guard: ExampleTest: assertion calls decreased: 2 -> 1\n"
            "test-weakening-guard: ExampleTest: test disable added: Ignore (1)\n"
            "test-weakening-guard: FAILED - add 'Test change reason: <at least 20 characters>' to the PR description or restore the tests.\n"
        ))

    def test_move_between_files_in_same_class_passes(self):
        other = "class ExampleTest {\n    @Test fun other() { assertTrue(true) }\n}\n"
        self.assertEqual([], self.check_diff({OLD_PATH: BASE, NEW_PATH: other}, {OLD_PATH: other, NEW_PATH: BASE}))
        self.assertEqual([], self.check_diff({OLD_PATH: BASE}, {NEW_PATH: BASE}))

    def test_python_tests_assertions_and_skip(self):
        before = "import unittest\nclass ExampleTest(unittest.TestCase):\n    def test_one(self):\n        assert True\n        self.assertEqual(1, 1)\n"
        after = before.replace("    def test_one", "    @unittest.skip('later')\n    def test_one").replace("        assert True\n", "")
        problems = self.check_diff({PY_PATH: before}, {PY_PATH: after})
        self.assertIn("ExampleTest: assertion calls decreased: 2 -> 1", problems)
        self.assertTrue(any("test disable added: unittest.skip" in item for item in problems))
        self.assertTrue(any("removed or renamed" in item for item in self.check_diff({PY_PATH: before}, {PY_PATH: after.replace('test_one', 'test_two')})))

    def test_ignore_comments_and_strings(self):
        after = BASE.replace("verify(mock)", 'verify(mock)\n        val x = "assertEquals(1, 2) @Ignore" // @Test fun fake()')
        self.assertEqual([], self.check_diff({OLD_PATH: BASE}, {OLD_PATH: after}))

    def test_event_fallback(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory(dir="/tmp/agent-scratch/gate-test-guard") as temp:
            event = Path(temp) / "event.json"
            event.write_text(json.dumps({"pull_request": {"body": REASON}}))
            with patch.dict(os.environ, {"PR_BODY": "", "GITHUB_EVENT_PATH": str(event)}, clear=True):
                self.assertEqual(REASON, guard.pr_body())
            with patch.dict(os.environ, {"PR_BODY": "explicit", "GITHUB_EVENT_PATH": str(event)}, clear=True):
                self.assertEqual("explicit", guard.pr_body())


class InputValidationTest(unittest.TestCase):
    def test_long_reason_lines_finish_quickly(self):
        import subprocess
        code = (
            "import importlib.util; "
            f"spec = importlib.util.spec_from_file_location('guard', {str(GUARD_PATH)!r}); "
            "guard = importlib.util.module_from_spec(spec); spec.loader.exec_module(guard); "
            "assert not guard.valid_reason('Test change reason:' + ' ' * 1000000); "
            "assert not guard.valid_reason(' ' * 1000000 + 'wrong'); "
            "assert guard.valid_reason(' ' * 1000000 + 'Test change reason: ' + 'x' * 20)"
        )
        subprocess.run([sys.executable, "-c", code], check=True, timeout=3)

    def test_option_like_diff_base_is_rejected_before_git(self):
        with patch.object(guard, "git") as git:
            with self.assertRaisesRegex(ValueError, "diff-base must be a branch, tag or commit name"):
                guard.check(Path("/fixture"), "--help")
            git.assert_not_called()


if __name__ == "__main__":
    unittest.main()
