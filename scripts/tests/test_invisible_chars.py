"""Literal invisible and bidi characters fail; escapes, a leading BOM and emoji joiners pass."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("invisible", Path(__file__).resolve().parents[1] / "ci/check-invisible-chars.py")
invisible = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(invisible)

# Every invisible character is built from an escape: this file must pass the check it tests.
RLO = "\u202e"
ZWJ = "\u200d"
BOM = "\ufeff"


class InvisibleCharsTest(unittest.TestCase):
    def test_every_listed_code_point_is_reported_with_its_position(self):
        for code in (0x200B, 0x200F, 0x202A, 0x202E, 0x2060, 0x2064, 0x2066, 0x2069, 0xFEFF, 0x061C):
            with self.subTest(code=hex(code)):
                self.assertEqual([(2, 6, code)], list(invisible.findings("val a = 1\nval b" + chr(code) + " = 2\n")))

    def test_escapes_and_ordinary_unicode_pass(self):
        self.assertEqual([], list(invisible.findings('val rtl = "\\u202E"\nval accent = "caf\u00e9"\n')))

    def test_leading_bom_passes_but_a_later_one_fails(self):
        self.assertEqual([], list(invisible.findings(BOM + "package a\n")))
        self.assertEqual([(1, 2, 0xFEFF)], list(invisible.findings("a" + BOM)))

    def test_zero_width_joiner_passes_only_between_emoji(self):
        family = "\U0001F468" + ZWJ + "\U0001F469" + ZWJ + "\U0001F467"
        heart = "\u2764\ufe0f" + ZWJ + "\U0001F525"
        self.assertEqual([], list(invisible.findings(family + " " + heart)))
        self.assertEqual([(1, 2, 0x200D)], list(invisible.findings("a" + ZWJ + "b")))
        self.assertEqual([(1, 1, 0x200D)], list(invisible.findings(ZWJ + "\U0001F525")))

    def test_check_reports_relative_paths_with_the_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src" / "A.kt"
            source.parent.mkdir()
            source.write_text('val a = "x' + RLO + 'y"\n', encoding="utf-8")
            errors = invisible.check(root, [source])
        self.assertEqual(1, len(errors))
        self.assertTrue(errors[0].startswith("src/A.kt:1:11: literal U+202E"), errors[0])
        self.assertIn("\\u202E", errors[0])


if __name__ == "__main__":
    unittest.main()
