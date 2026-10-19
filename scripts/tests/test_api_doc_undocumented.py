"""Exercise the Dokka diagnostic ratchet, not an approximation of source KDoc coverage."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "api_undocumented", Path(__file__).resolve().parents[1] / "ci/check-api-doc-undocumented.py",
)
ratchet = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ratchet)

CLASS = ":sharedLogic example/UndocumentedClock/ (main)"
OVERLOAD_ONE = ":sharedLogic example/ApiClock/tick/#kotlin.Int/ (main)"
OVERLOAD_TWO = ":sharedLogic example/ApiClock/tick/#kotlin.String/ (main)"


def build_log(signatures=()):
    tasks = "\n".join(f"> Task {module}:dokkaGenerateModuleHtml" for module in ratchet.MONITORED_MODULES)
    warnings = "\n".join("w: [" + signature.split(" ", 1)[0] + ":dokkaGenerateModuleHtml] Undocumented: " + signature.split(" ", 1)[1] for signature in signatures)
    return f"{tasks}\n{warnings}\n> Task :dokkaGeneratePublicationHtml\nBUILD SUCCESSFUL in 10s\n"


class ApiDocUndocumentedTest(unittest.TestCase):
    def test_retains_dokka_callable_and_platform_identity_with_duplicate_diagnostics(self):
        android = CLASS.replace("(main)", "(android)")
        output = "\x1b[33m" + build_log([CLASS]) + "\x1b[0m\n"
        output += build_log([CLASS, OVERLOAD_ONE, OVERLOAD_TWO, android])
        self.assertEqual({CLASS, OVERLOAD_ONE, OVERLOAD_TWO, android}, ratchet.warnings_from_log(output))

    def test_preserves_whitespace_inside_quoted_kotlin_identifiers(self):
        one_space = CLASS.replace("UndocumentedClock", "Clock name")
        two_spaces = CLASS.replace("UndocumentedClock", "Clock  name")
        self.assertEqual({one_space, two_spaces}, ratchet.warnings_from_log(build_log({one_space, two_spaces})))

    def test_malformed_diagnostic_is_not_ignored(self):
        with self.assertRaises(ValueError):
            ratchet.warnings_from_log("Undocumented: \n")

    def test_requires_success_and_fresh_execution_for_every_monitored_module(self):
        valid = build_log()
        ratchet.require_fresh_generation(valid)
        ratchet.require_fresh_generation(valid.replace(
            "> Task :dokkaGeneratePublicationHtml", "> Task :dokkaGeneratePublicationHtml UP-TO-DATE",
        ))
        for broken in ("", valid.replace("BUILD SUCCESSFUL", "BUILD FAILED"), valid.replace(
            "> Task :plugin-api:dokkaGenerateModuleHtml", "> Task :plugin-api:dokkaGenerateModuleHtml FROM-CACHE",
        ), valid.replace(
            "> Task :core:runtime:dokkaGenerateModuleHtml\n", "",
        ), valid.replace(
            "> Task :sharedLogic:dokkaGenerateModuleHtml", "> Task :sharedLogic:dokkaGenerateModuleHtml UP-TO-DATE",
        )):
            with self.subTest(log=broken), self.assertRaises(ValueError):
                ratchet.require_fresh_generation(broken)

    def test_rendering_is_deterministic_and_baseline_requires_sorted_unique_signatures(self):
        self.assertEqual(ratchet.render_baseline({CLASS, OVERLOAD_ONE}), ratchet.render_baseline([OVERLOAD_ONE, CLASS]))
        self.assertEqual({CLASS, OVERLOAD_ONE}, ratchet.read_baseline(ratchet.render_baseline({CLASS, OVERLOAD_ONE})))
        for invalid in (CLASS + "\n" + CLASS, CLASS + "\n" + OVERLOAD_ONE, "not-a-signature"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                ratchet.read_baseline(invalid)

    def test_same_count_different_overload_fails_new_and_stale_checks(self):
        errors = ratchet.compare({OVERLOAD_TWO}, {OVERLOAD_ONE})
        self.assertEqual(2, len(errors))
        self.assertIn("New undocumented", errors[0])
        self.assertIn("Stale baseline", errors[1])

    def test_documenting_an_api_requires_removing_its_stale_baseline_entry(self):
        self.assertIn("Stale baseline", ratchet.compare(set(), {CLASS})[0])
        self.assertEqual([], ratchet.compare(set(), set()))

    def test_runtime_cannot_be_baselined_even_when_bootstrapping(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log = root / "dokka.log"
            log.write_text(build_log({CLASS.replace(":sharedLogic", ":core:runtime")}), encoding="utf-8")
            self.assertEqual(1, ratchet.main([str(log), "--root", str(root), "--write-baseline"]))
            self.assertFalse((root / ratchet.BASELINE).exists())

    def test_module_identity_is_not_interchangeable(self):
        other_module = CLASS.replace(":sharedLogic", ":plugin-api")
        self.assertEqual(2, len(ratchet.compare({other_module}, {CLASS})))

    def test_unscoped_or_unmonitored_warnings_fail_closed(self):
        for line in ("Undocumented: example/Foo/ (main)",
                     "w: [:unexpected:dokkaGenerateModuleHtml] Undocumented: example/Foo/ (main)"):
            with self.subTest(line=line), self.assertRaises(ValueError):
                ratchet.warnings_from_log(line)

    def test_cli_checks_real_log_and_rejects_baseline_growth_against_git(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            baseline = root / ratchet.BASELINE
            baseline.parent.mkdir(parents=True)
            baseline.write_text(ratchet.render_baseline({CLASS}), encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "baseline"], cwd=root, check=True)
            log = root / "dokka.log"
            log.write_text(build_log({CLASS}), encoding="utf-8")
            args = [str(log), "--root", str(root), "--diff-base", "HEAD"]
            self.assertEqual(0, ratchet.main(args))
            log.write_text(build_log({CLASS, OVERLOAD_ONE}), encoding="utf-8")
            original = baseline.read_bytes()
            self.assertEqual(1, ratchet.main(args))
            self.assertEqual(original, baseline.read_bytes(), "check must not rewrite its baseline")
            self.assertEqual(0, ratchet.main(args + ["--write-baseline"]))
            self.assertEqual(1, ratchet.main(args), "recording new debt cannot silently bless it")
            log.write_text(build_log(), encoding="utf-8")
            baseline.write_text(ratchet.render_baseline(set()), encoding="utf-8")
            self.assertEqual(0, ratchet.main(args), "the baseline may shrink")


if __name__ == "__main__":
    unittest.main()
