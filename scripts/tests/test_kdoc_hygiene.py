"""Tracking IDs must not leak through published documentation comments."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("hygiene", Path(__file__).resolve().parents[1] / "ci/check-kdoc-hygiene.py")
hygiene = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(hygiene)


class KdocHygieneTest(unittest.TestCase):
    def test_real_kdoc_only(self):
        text = '''val normal = "/** letta-mobile-abcde */"
val raw = """/** letta-mobile-abcde */"""
// /** letta-mobile-abcde */
/* ordinary /* nested */ letta-mobile-abcde */
/** Real API documentation: letta-mobile-abcde.1 and CodeScene #123, CodeScene 456. */
class Api
'''
        blocks = list(hygiene.kdoc_blocks(text))
        self.assertEqual(1, len(blocks))
        self.assertEqual(["letta-mobile-abcde.1", "CodeScene #123", "CodeScene 456"], hygiene.TRACKER.findall(blocks[0][2]))

    def test_nested_kdoc_and_escaped_quotes(self):
        text = r'''val name = "escaped \" /** letta-mobile-abcde */"
/** Outer /* nested */ letta-mobile-abcde.2/.3. */'''
        blocks = list(hygiene.kdoc_blocks(text))
        self.assertEqual(1, len(blocks))
        self.assertEqual(["letta-mobile-abcde.2/.3"], hygiene.TRACKER.findall(blocks[0][2]))

    def test_unterminated_comment_fails(self):
        with self.assertRaisesRegex(ValueError, "Unterminated"):
            list(hygiene.kdoc_blocks("/** unfinished"))

    def test_new_production_kdoc_fails_but_test_sources_do_not(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            build = root / "android-compose"
            build.mkdir()
            (build / "build.gradle.kts").write_text('dependencies { add("dokka", project(":contract")) }', encoding="utf-8")
            module = build / "contract"
            for source in ("commonMain", "commonTest"):
                path = module / "src" / source / "kotlin" / "Api.kt"
                path.parent.mkdir(parents=True)
                path.write_text("package example\n/** letta-mobile-abcde */\nclass Api\n", encoding="utf-8")
            errors = hygiene.check(root)
            self.assertEqual(1, len(errors))
            self.assertIn("Api.kt:2:", errors[0])
            self.assertIn("commonMain", errors[0])
            path = module / "src/commonMain/kotlin/Api.kt"
            path.write_text("package example\n/** A portable contract for host integrations. */\nclass Api\n", encoding="utf-8")
            self.assertEqual([], hygiene.check(root))


if __name__ == "__main__":
    unittest.main()
