"""Progress is advisory, exact in its counts, and conservative about source changes."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("progress", Path(__file__).resolve().parents[1] / "ci/api-doc-progress.py")
progress = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(progress)

ONE = ":plugin-api example/Clock/tick/#kotlin.Int/ (common)"
TWO = ":plugin-api example/Clock/tick/#kotlin.String/ (common)"
SOURCE = "android-compose/plugin-api/src/commonMain/kotlin/Clock file.kt"


def page(identity, path):
    return f'''<div id="content" pageIds="{identity}PointingToDeclaration//123"><br/><img src="example.png"><span data-element-type="source-link"><span>(</span><a href="https://github.com/oculairmedia/letta-mobile/tree/abc/{path}">source</a></span></div>'''


class ProgressTest(unittest.TestCase):
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
        self.assertEqual(20, summary.count("- `"))
        self.assertIn("Page-level association only", summary)
        self.assertIn("full list", summary)


if __name__ == "__main__":
    unittest.main()
