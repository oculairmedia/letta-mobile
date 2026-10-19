"""Comment-only edits must not expand the required Kotlin policy scan."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("changed_code", Path(__file__).resolve().parents[1] / "ci/changed-kotlin-code.py")
selector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(selector)


class ChangedKotlinCodeTest(unittest.TestCase):
    def test_push_and_local_contexts_use_main_and_prs_use_the_target_branch(self):
        for environment, expected in (
            ({}, "origin/main"),
            ({"GITHUB_BASE_REF": ""}, "origin/main"),
            ({"GITHUB_BASE_REF": "  "}, "origin/main"),
            ({"GITHUB_BASE_REF": "main"}, "origin/main"),
            ({"GITHUB_BASE_REF": "codex/stack-base"}, "origin/codex/stack-base"),
        ):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                self.assertEqual(expected, selector.default_base())

    def test_tree_paths_and_batch_contents_use_distinct_delimiters(self):
        oid = b"a" * 40
        path = "android-compose/a space/a\nname.kt"
        tree = b"100644 blob " + oid + b"\t" + path.encode() + b"\0"
        self.assertEqual({path: oid}, selector.tree_blobs(tree))
        body = 'val x = "λ\\n"\n// header-like text\n'.encode("utf-8")
        batch = oid + b" blob " + str(len(body)).encode() + b"\n" + body + b"\n"
        with patch.object(selector, "git", return_value=batch) as git:
            self.assertEqual({oid: body}, selector.read_blobs(Path("."), [oid, oid]))
            git.assert_called_once_with(Path("."), "cat-file", "--batch", input=oid + b"\n")

    def test_bad_or_truncated_batch_objects_fail_closed(self):
        oid = b"a" * 40
        for output in (oid + b" missing\n", oid + b" tree 0\n\n",
                       oid + b" blob 20\nshort\n", oid + b" blob 1\nx\nextra"):
            with self.subTest(output=output), patch.object(selector, "git", return_value=output):
                with self.assertRaises(ValueError):
                    selector.read_blobs(Path("."), [oid])
        with self.assertRaises(ValueError):
            selector.read_blobs(Path("."), [b"HEAD:path\nother"])

    def test_comments_nested_blocks_and_documentation_do_not_change_tokens(self):
        before = '/** old\n * text */\nval x = "/* literal */" // old\n'
        after = '/** meaningful\n * new /* nested */ documentation */\nval x = "/* literal */" // improved\n'
        self.assertEqual(selector.code_tokens(before), selector.code_tokens(after))

    def test_literals_operators_and_statement_boundaries_remain_significant(self):
        pairs = [('val x = "a b"', 'val x = "a  b"'),
                 ('val x = """// old"""', 'val x = """// new"""'),
                 ('a += b', 'a + = b'), ('return\nx', 'return x'),
                 ('val x = "${a}"', 'val x = "${b}"'),
                 ("val x = '\\n'", "val x = '\\t'"),
                 ('val `a b` = 1', 'val `a  b` = 1')]
        for before, after in pairs:
            with self.subTest(before=before):
                self.assertNotEqual(selector.code_tokens(before), selector.code_tokens(after))

    def test_unterminated_input_fails_closed(self):
        for text in ('/* incomplete', 'val x = "oops', 'val x = """oops'):
            with self.assertRaises(ValueError):
                selector.code_tokens(text)

    def test_crlf_and_lf_comments_compare_consistently(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "Example.kt"
            original = b'/** old */\r\nval x = """first\r\nsecond""" // old\r\n'
            source.write_bytes(b'/** improved */\nval x = """first\nsecond""" // new\n')
            self.assertFalse(selector.code_changed(source, original))
            source.write_bytes(b'/** improved */\r\nval x = """first\r\nchanged"""\r\n')
            self.assertTrue(selector.code_changed(source, original))

    def test_nested_template_strings_cannot_hide_following_code(self):
        prefix = 'val x = "${call("a // ${nested("}")}")}"; '
        self.assertNotEqual(selector.code_tokens(prefix + 'unsafe()'), selector.code_tokens(prefix + 'safe()'))
        self.assertEqual(selector.code_tokens(prefix + '/** old */ safe()'), selector.code_tokens(prefix + '/** new */ safe()'))

    def test_git_selection_includes_staged_untracked_and_literal_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*args):
                return selector.git(root, *args)
            git('init', '--quiet')
            git('config', 'user.email', 'test@example.invalid')
            git('config', 'user.name', 'Test')
            sources = root / 'android-compose/sample'
            sources.mkdir(parents=True)
            for name in ('Docs', 'Literal', 'Staged', 'Broken', 'Deleted'):
                (sources / f'{name}.kt').write_text('/** old */\nval x = "value"\n', encoding='utf-8')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD').decode().strip()
            git('update-ref', 'refs/remotes/origin/main', base)
            (sources / 'Docs.kt').write_text('/** updated */\nval x = "value"\n', encoding='utf-8')
            (sources / 'Staged.kt').write_text('val x = "staged"\n', encoding='utf-8')
            git('add', 'android-compose/sample/Staged.kt')
            (sources / 'Literal.kt').write_text('val x = "new"\n', encoding='utf-8')
            (sources / 'Broken.kt').write_text('/* broken', encoding='utf-8')
            (sources / 'New.kt').write_text('val new = 1', encoding='utf-8')
            (sources / 'Deleted.kt').unlink()
            selected = {Path(path).stem for path in selector.changed_files(root, base)}
            self.assertEqual({'Staged', 'Literal', 'Broken', 'New'}, selected)
            with patch.dict(os.environ, {"GITHUB_BASE_REF": ""}):
                self.assertEqual(selected, {Path(path).stem for path in selector.changed_files(root, selector.default_base())})
            with patch.dict(os.environ, {"GITHUB_BASE_REF": "missing-pr-target"}):
                with self.assertRaises(subprocess.CalledProcessError):
                    list(selector.changed_files(root, selector.default_base()))
            with self.assertRaises(subprocess.CalledProcessError):
                list(selector.changed_files(root, 'missing-base'))
