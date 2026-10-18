"""Regression coverage for authored links into the generated API publication."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "api_links", Path(__file__).resolve().parents[1] / "ci/check-api-doc-links.py",
)
links = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(links)


class ApiDocLinksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.api = self.root / "api"
        self.api.mkdir()
        (self.api / "index.html").write_text("<h1>API</h1>", encoding="utf-8")
        module = self.api / "sharedLogic"
        module.mkdir()
        (module / "index.html").write_text(
            '<h1 id="a&amp;b">API</h1><a name="old-anchor"></a>', encoding="utf-8",
        )

    def test_pages_directory_indexes_queries_and_encoded_anchors(self):
        for suffix in ("", "sharedLogic/", "sharedLogic/index.html?view=all#a%26b",
                       "sharedLogic/#old-anchor"):
            with self.subTest(suffix=suffix):
                self.assertIsNone(links.check_link(links.API_URL + suffix, self.api))

    def test_missing_api_page_and_anchor_fail(self):
        self.assertIn("page is absent", links.check_link(links.API_URL + "gone/", self.api))
        self.assertIn("anchor #gone", links.check_link(links.API_URL + "#gone", self.api))

    def test_encoded_parent_paths_cannot_escape_generated_site(self):
        (self.root / "outside.html").write_text("outside", encoding="utf-8")
        self.assertIn("escapes", links.check_link(links.API_URL + "%2e%2e/outside.html", self.api))

    def test_extraction_ignores_examples_and_other_sites(self):
        text = f'''[API]({links.API_URL}sharedLogic/)
<{links.API_URL}sharedLogic/>
```markdown
[example]({links.API_URL}missing/)
```
~~~markdown
{links.API_URL}also-missing/
~~~
[different](https://example.com/api/missing/)
'''
        self.assertEqual([links.API_URL + "sharedLogic/"], links.published_links(text))

    def test_only_tracked_markdown_is_checked_and_failures_name_the_document(self):
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        (self.root / "guide.md").write_text(f"[type]({links.API_URL}removed/)", encoding="utf-8")
        (self.root / "scratch.md").write_text(f"[scratch]({links.API_URL}scratch/)", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", "guide.md"], check=True)
        count, errors = links.check_documents(self.root, self.api)
        self.assertEqual(1, count)
        self.assertEqual(1, len(errors))
        self.assertTrue(errors[0].startswith("guide.md:"))

    def test_missing_publication_cannot_pass_as_an_empty_link_scan(self):
        (self.api / "index.html").unlink()
        result = subprocess.run(
            [sys.executable, str(Path(links.__file__)), "--api-root", str(self.api)],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("generate the API publication first", result.stderr)


if __name__ == "__main__":
    unittest.main()
