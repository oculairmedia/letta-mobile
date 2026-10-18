"""Hermetic Git fixtures for documentation navigation generation."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "docs/documentation-index.py"
SPEC = importlib.util.spec_from_file_location("documentation_index", SCRIPT)
INDEXER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INDEXER)


class DocumentationIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.authored = "# Curated introduction\n\nKeep **this** prose.\n\n"
        self.tail = "\n\n## Authored footer\n\nKeep this too.\n"
        self.write("docs/README.md", self.authored + INDEXER.START + "\nstale\n" + INDEXER.END + self.tail)

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                              capture_output=True)

    def write(self, name, text, tracked=True):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8"))
        if tracked:
            self.git("add", "--", name)

    def run_index(self, mode):
        return subprocess.run([sys.executable, str(SCRIPT), mode, "--root", str(self.root)],
                              capture_output=True, text=True)

    def current(self):
        return (self.root / "docs/README.md").read_text(encoding="utf-8")

    def test_stale_write_idempotence_and_authored_prose(self):
        self.write("docs/z.md", "# Last\n")
        self.write("docs/a.md", "# First\n")
        self.write("docs/reference/c.md", "# Child\n")
        self.assertEqual(1, self.run_index("--check").returncode)
        self.assertEqual(0, self.run_index("--write").returncode)
        result = self.current()
        self.assertTrue(result.startswith(self.authored + INDEXER.START))
        self.assertTrue(result.endswith(INDEXER.END + self.tail))
        self.assertLess(result.index("First"), result.index("Last"))
        self.assertLess(result.index("### General"), result.index("### reference"))
        self.assertEqual(0, self.run_index("--check").returncode)
        self.assertEqual(0, self.run_index("--write").returncode)
        self.assertEqual(result, self.current())

    def test_added_renamed_and_unstaged_deleted_documents(self):
        self.write("docs/old.md", "# Old\n")
        self.assertEqual(0, self.run_index("--write").returncode)
        self.git("mv", "docs/old.md", "docs/new.md")
        self.write("docs/added.md", "# Added\n")
        self.assertEqual(1, self.run_index("--check").returncode)
        self.assertEqual(0, self.run_index("--write").returncode)
        self.assertNotIn("(old.md)", self.current())
        self.assertIn("(new.md)", self.current())
        self.assertIn("(added.md)", self.current())
        (self.root / "docs/new.md").unlink()  # Git still tracks it until deletion is staged.
        self.assertEqual(0, self.run_index("--write").returncode)
        self.assertNotIn("(new.md)", self.current())
        self.assertEqual(0, self.run_index("--check").returncode)

    def test_title_change_duplicates_and_missing_rows_are_stale(self):
        self.write("docs/a.md", "# Original\n")
        self.run_index("--write")
        self.write("docs/a.md", "# Changed\n")
        self.assertEqual(1, self.run_index("--check").returncode)
        self.run_index("--write")
        good = self.current()
        self.write("docs/README.md", good.replace("- [Changed](a.md)", "- [Changed](a.md)\n- [Changed](a.md)"))
        self.assertEqual(1, self.run_index("--check").returncode)
        self.write("docs/README.md", good.replace("- [Changed](a.md)\n", ""))
        self.assertEqual(1, self.run_index("--check").returncode)

    def test_unicode_spaces_punctuation_fences_and_scope(self):
        self.write("docs/nested group/café [x](a)%.md", "```md\n# Wrong\n```\n# Café [x] *literal* <tag> & \\ ###\n")
        self.write("docs/fallback.md", "No heading\n")
        self.write("docs/ignored.md", "# Untracked\n", tracked=False)
        self.write("README.md", "# Root outside scope\n")
        self.write("android-compose/build/dokka/guide.md", "# Build outside scope\n")
        self.run_index("--write")
        result = self.current()
        self.assertIn("nested%20group/caf%C3%A9%20%5Bx%5D%28a%29%25.md", result)
        self.assertIn("Café \\[x\\] \\*literal\\* &lt;tag&gt; &amp; \\\\".rstrip(), result)
        self.assertIn("[fallback](fallback.md)", result)
        for absent in ("Wrong", "Untracked", "Root outside scope", "Build outside scope"):
            self.assertNotIn(absent, result)

    def test_ambiguous_markers_fail_without_writing(self):
        for text in ("no markers", INDEXER.END + INDEXER.START,
                     INDEXER.START + INDEXER.START + INDEXER.END):
            self.write("docs/README.md", text)
            self.assertEqual(2, self.run_index("--write").returncode)
            self.assertEqual(text, self.current())

    def test_setext_heading_after_front_matter(self):
        self.write("docs/a.MD", "---\n# Metadata comment\nname: metadata\n---\n\nSetext title\n============\n")
        self.run_index("--write")
        self.assertIn("[Setext title](a.MD)", self.current())
        self.assertNotIn("Metadata comment", self.current())

    def test_long_fence_does_not_close_on_shorter_fence_or_language_tag(self):
        self.write("docs/fenced.md", "````md\n```\n# Still code\n````python\n# Also code\n````\n# Real &amp; [title]\n")
        self.run_index("--write")
        result = self.current()
        self.assertIn("[Real &amp; \\[title\\]](fenced.md)", result)
        self.assertNotIn("Still code", result)
        self.assertNotIn("Also code", result)


if __name__ == "__main__":
    unittest.main()
