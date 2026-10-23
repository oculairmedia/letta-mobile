"""package_headroom.py must count exactly what CI's structure export and report count."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))
import package_headroom as headroom  # noqa: E402

FIXTURE = Path(__file__).resolve().parent / "fixtures/structure-parity"
EMPTY_BASELINE = {"schema": 1, "defaultTypeLimit": 50, "packageTypeLimits": {}, "cyclicImportEdges": []}


class PackageHeadroomTest(unittest.TestCase):
    def setUp(self):
        self.structure = headroom.scan(FIXTURE)
        self.report = self.structure.report()

    def test_export_equals_the_golden_the_konsist_export_is_pinned_to(self):
        # StructureExportFixtureTest asserts the architecture-tests export of this fixture equals
        # the same golden, so the two readers agree on scope, types and import edges.
        golden = (FIXTURE / "expected-export.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(sorted(golden), sorted(headroom.export_lines(FIXTURE)))

    def test_counts_nested_and_companion_types_once_per_module_and_skip_local_test_vendored(self):
        counts = {row["package"]: row["types"] for row in self.report["packages"]}
        # 19 alpha types (expect/actual Platform once) plus gamma's own fx.alpha.Outer.
        self.assertEqual({"fx.alpha": 20, "fx.alpha.playground": 1, "fx.beta": 2, "fx.beta.sub": 1, "fx.gamma": 3}, counts)

    def test_cycles_use_the_longest_declared_package_and_drop_non_member_imports(self):
        self.assertEqual(["fx.alpha -> fx.beta", "fx.beta -> fx.alpha", "fx.beta.sub -> fx.gamma", "fx.gamma -> fx.beta.sub"],
                         self.report["cyclicImportEdges"])

    def test_would_import_reports_every_edge_a_planned_import_pulls_into_a_cycle(self):
        planned = [headroom.parse_edge("fx.beta.sub->fx.alpha")]
        self.assertEqual(["fx.alpha -> fx.beta.sub", "fx.alpha -> fx.gamma", "fx.beta.sub -> fx.alpha"],
                         headroom.would_import(self.structure, EMPTY_BASELINE, planned))
        self.assertEqual([], headroom.would_import(self.structure, EMPTY_BASELINE, [("fx.alpha", "fx.alpha.playground.new")]))

    def test_headroom_and_contract_errors_follow_the_structure_baseline(self):
        baseline = dict(EMPTY_BASELINE, packageTypeLimits={"fx.alpha": 60})
        self.assertIn("fx.alpha: 20/60 types, headroom 40", headroom.headroom_rows(self.report, baseline, ["fx.alpha"])[0])
        self.assertIn("FULL", headroom.headroom_rows({"packages": [{"package": "p", "types": 50}]}, EMPTY_BASELINE, ["p"])[0])
        errors = headroom.contract_errors(self.report, baseline)
        self.assertIn("fx.alpha: lower/remove stale type baseline 60 to 50", errors)
        self.assertIn("New cyclic import edge: fx.alpha -> fx.beta", errors)
        over = {"packages": [{"package": "p", "types": 51}], "cyclicImportEdges": []}
        self.assertEqual(["p: 51 types exceeds 50; split the package"], headroom.contract_errors(over, EMPTY_BASELINE))

    def test_compare_names_each_package_that_differs_from_a_ci_report(self):
        self.assertEqual([], headroom.compare(self.report, self.report))
        drifted = dict(self.report, packages=[dict(row, types=row["types"] + 1) for row in self.report["packages"]])
        self.assertEqual(5, len(headroom.compare(self.report, drifted)))

    def test_changed_packages_are_those_of_touched_production_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            shutil.copytree(FIXTURE / "android-compose", repo / "android-compose")
            git = lambda *args: subprocess.run(["git", "-C", tmp, *args], check=True, capture_output=True)  # noqa: E731
            git("init", "-q")
            git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "base", "--allow-empty")
            git("add", ".")
            git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "fixture")
            sub = repo / "android-compose/core/beta/src/main/kotlin/fx/beta/sub/Sub.kt"
            sub.write_text(sub.read_text(encoding="utf-8") + "\ninterface Added\n", encoding="utf-8")
            (repo / "android-compose/gamma/src/main/kotlin/fx/alpha/Untracked.kt").write_text("package fx.alpha\n", encoding="utf-8")
            self.assertEqual(["fx.alpha", "fx.beta.sub"], headroom.changed_packages(repo, "HEAD", headroom.scan(repo)))


if __name__ == "__main__":
    unittest.main()
