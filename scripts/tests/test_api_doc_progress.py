"""Progress is advisory, exact in its counts, and conservative about source changes."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("progress", Path(__file__).resolve().parents[1] / "ci/api-doc-progress.py")
progress = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(progress)

ONE = ":plugin-api example/Clock/tick/#kotlin.Int/ (common)"
TWO = ":plugin-api example/Clock/tick/#kotlin.String/ (common)"
SOURCE = "android-compose/plugin-api/src/commonMain/kotlin/Clock file.kt"


def page(identity, path):
    return f'''<div id="content" pageIds="{identity}PointingToDeclaration//123"><br/><img src="example.png"><span data-element-type="source-link"><span>(</span><a href="https://github.com/oculairmedia/letta-mobile/tree/abc/{path}">source</a></span></div>'''


class ProgressTest(unittest.TestCase):
    def test_only_runner_provided_summary_may_be_outside_repository(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            runner_file = Path(temporary) / "runner-summary.md"
            with patch.dict(progress.os.environ, {"GITHUB_STEP_SUMMARY": str(runner_file)}):
                self.assertEqual(runner_file, progress.summary_path(runner_file, root))
                with self.assertRaises(ValueError):
                    progress.summary_path(Path(temporary) / "other.md", root)
            with patch.dict(progress.os.environ, {}, clear=True):
                with self.assertRaises(ValueError):
                    progress.summary_path(runner_file, root)

    def test_reductions_never_claim_documentation_gain_or_coverage(self):
        summary = progress.render_summary({ONE}, {ONE, TWO}, [])
        self.assertIn("| `:plugin-api` | 1 | 2 | -1 | 1 |", summary)
        self.assertIn("Resolved or removed (unclassified)", summary)
        self.assertNotIn("50%", summary)
        self.assertIn("initial baseline", progress.render_summary({ONE}, None, []))

    def test_body_source_controls_only_not_navigation(self):
        parsed = progress.ApiPage()
        parsed.feed(f'<nav><span data-element-type="source-link"><a href="https://github.com/oculairmedia/letta-mobile/tree/abc/wrong.kt">source</a></span></nav>')
        parsed.feed(page(":plugin-api::example/Clock/tick/#kotlin.Int/", SOURCE.replace(" ", "%20")))
        parsed.feed('<a href="https://github.com/oculairmedia/letta-mobile/tree/abc/also-wrong.kt">navigation</a>')
        parsed.feed('<footer><span data-element-type="source-link"><a href="https://github.com/oculairmedia/letta-mobile/tree/abc/footer-wrong.kt">source</a></span></footer>')
        self.assertEqual({SOURCE}, parsed.paths)

    def test_full_overload_identity_and_changed_file_association(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            directory = root / "android-compose/plugin-api/build/dokka-module/html/module"
            directory.mkdir(parents=True)
            (directory / "tick.html").write_text(page(":plugin-api::example/Clock/tick/#kotlin.Int/", SOURCE), encoding="utf-8")
            self.assertEqual([(ONE, SOURCE)], progress.page_gaps(root, {ONE, TWO}, {SOURCE}))
            self.assertEqual([], progress.page_gaps(root, {ONE, TWO}, {"Unrelated.kt"}))

    def test_summary_is_capped_and_labels_page_level_evidence(self):
        summary = progress.render_summary({ONE}, {ONE}, [(ONE, f"file{i}.kt") for i in range(25)])
        self.assertEqual(20, sum(line.startswith("- `") for line in summary.splitlines()))
        self.assertIn(f"- `{ONE}` - `file0.kt`", summary)
        self.assertIn("Page-level association only", summary)
        self.assertIn("full list", summary)


if __name__ == "__main__":
    unittest.main()
