"""KMP leak gate tests: python3 -m unittest discover -s scripts/ci -p 'test_kmp_common_leaks.py' -v."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("kmp_common_leaks", Path(__file__).with_name("check-kmp-common-leaks.py"))
leaks = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(leaks)

CHECKER = Path(leaks.__file__)
COMMON = "android-compose/fake/src/commonMain/kotlin/Fake.kt"
CLEAN = 'val usage = "ok"\n'


class KmpCommonLeakTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        # The smallest tree the scanner treats as the repository: one module with common source.
        self.write(COMMON, CLEAN)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        return target

    def findings(self, path=COMMON):
        """Errors for one file, plus the CLI's verdict over the whole fixture repo."""
        errors = leaks.check(self.root, [self.root / path])[1]
        result = self.run_cli()
        return errors, result

    def run_cli(self, *args):
        return subprocess.run(["python3", str(CHECKER), "--root", str(self.root), *args],
                              capture_output=True, text=True)

    def assert_flagged(self, snippet, message, line=2):
        """[snippet] appended to the common source file must be flagged at [line]."""
        self.write(COMMON, CLEAN + snippet)
        errors, result = self.findings()
        self.assertEqual(1, len(errors), errors)
        self.assertTrue(errors[0].startswith(f"{COMMON}:{line}: "), errors)
        self.assertIn(message, errors[0])
        self.assertEqual(1, result.returncode)
        self.assertIn(message, result.stderr)

    def assert_clean(self):
        errors, result = self.findings()
        self.assertEqual([], errors)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    # --- every leak rule flags -------------------------------------------------------------------

    def test_java_and_javax_imports_flag(self):
        self.assert_flagged("import java.io.File\n", "import java.io.File is JVM-only")
        self.assert_flagged("import javax.crypto.Cipher\n", "import javax.crypto.Cipher is JVM-only")

    def test_string_format_flags(self):
        self.assert_flagged('val text = String.format("%d", 1)\n', "String.format( is JVM-only")
        self.assert_flagged('val spaced = String . format ("x")\n', "String.format( is JVM-only")

    def test_thread_sleep_flags(self):
        self.assert_flagged("val done = Thread.sleep(10)\n", "Thread.sleep( is JVM-only")
        self.assert_flagged("val done = Thread . sleep (\n    10\n)\n", "Thread.sleep( is JVM-only")

    def test_system_clock_and_getenv_flags(self):
        for call in ("System.currentTimeMillis()", "System.nanoTime()", "System.getenv(\"HOME\")"):
            with self.subTest(api=call):
                self.assert_flagged(f"val at = {call}\n", f"{call[:call.index('(')]}( is JVM-only")

    def test_string_to_byte_array_flags_literal_receiver(self):
        self.assert_flagged('val bytes = "payload".toByteArray()\n', "String.toByteArray( is JVM-only")
        self.assert_flagged('val bytes = """payload""".toByteArray()\n', "String.toByteArray( is JVM-only")
        self.assert_flagged('val bytes = "" .toByteArray()\n', "String.toByteArray( is JVM-only")

    def test_string_to_byte_array_flags_decoded_receiver(self):
        self.assert_flagged("val bytes = text.toString().toByteArray()\n", "String.toByteArray( is JVM-only")
        self.assert_flagged("val bytes = base64.decodeToString().toByteArray()\n",
                            "String.toByteArray( is JVM-only")

    def test_string_to_byte_array_flags_charset_overload(self):
        self.assert_flagged("val bytes = raw.toByteArray(Charsets.UTF_8)\n",
                            "toByteArray(charset) is the JVM-only String overload")
        self.assert_flagged("val bytes = raw.toByteArray(charset = Charsets.ISO_8859_1)\n",
                            "toByteArray(charset) is the JVM-only String overload")

    def test_leak_inside_a_block_is_found_on_its_line(self):
        self.write(COMMON, CLEAN + "fun demo() {\n // filler\n    val at = System.currentTimeMillis()\n}\n")
        errors, _ = self.findings()
        self.assertEqual([f"{COMMON}:4: System.currentTimeMillis( is JVM-only: "
                         "use kotlin.time (TimeSource/Clock) or an expect/actual"], errors)

    def test_several_leaks_all_report(self):
        self.write(COMMON, "import java.io.File\nval a = Thread.sleep(10)\n")
        errors, result = self.findings()
        self.assertEqual(2, len(errors), errors)
        self.assertIn(":1: import java.io.File is JVM-only", errors[0])
        self.assertIn(":2: Thread.sleep( is JVM-only", errors[1])
        self.assertEqual(1, result.returncode)
        self.assertEqual(2, result.stderr.count("JVM-only"))

    # --- comments, strings and other source sets never flag ---------------------------------------

    def test_comments_and_string_literals_never_flag(self):
        self.write(COMMON, "\n".join((
            "// import java.io.File",
            "/* Thread.sleep( and String.format( and System.currentTimeMillis( */",
            "/** KDoc: `import java.io.File`, see `String.format(` above. */",
            'val prose = "import java.io.File said the Thread.sleep( call, System.getenv( too"',
            'val json = "{\\"escape\\"} then \\"payload\\".toByteArray( of Fake.kt"',
            'val nested = "/* String.format( is not inside a comment either */"',
            CLEAN,
        )) + "\n")
        self.assert_clean()

    def test_common_test_source_set_is_scanned(self):
        self.write("android-compose/fake/src/commonTest/kotlin/FakeTest.kt", "val anySleep = Thread.sleep(1)\n")
        scanned, errors = leaks.check(self.root, list(leaks.common_files(self.root)))
        self.assertEqual(2, scanned)
        self.assertEqual(["android-compose/fake/src/commonTest/kotlin/FakeTest.kt:1: "
                          "Thread.sleep( is JVM-only: use kotlinx.coroutines delay()"], errors)

    def test_common_kotlin_trees_recursively_and_prune_module_build_output(self):
        deep = "android-compose/fake/src/commonMain/kotlin/com/example/deep/Deep.kt"
        self.write(deep, "import java.util.UUID\n")
        self.write("android-compose/fake/build/src/commonMain/kotlin/Built.kt",
                   "val built = Thread.sleep(1)\n")
        scanned, errors = leaks.check(self.root, list(leaks.common_files(self.root)))
        self.assertEqual(2, scanned)  # the setUp fixture file plus the deep package file
        self.assertEqual([f"{deep}:1: import java.util.UUID is JVM-only: "
                         "use a multiplatform API or move the code to a platform source set"],
                         errors)

    def test_platform_and_non_common_source_sets_are_ignored(self):
        for source in ("jvmMain", "androidMain", "wasmJsMain", "hostMain", "main", "test"):
            with self.subTest(source=source):
                self.write(f"android-compose/fake/src/{source}/kotlin/Platform.kt",
                           "import java.io.File\nval now = System.currentTimeMillis()\n")
        self.write("android-compose/fake/src/commonMain/resources/Fake.kt", "import java.io.File\n")
        scanned, errors = leaks.check(self.root, list(leaks.common_files(self.root)))
        self.assertEqual(1, scanned)
        self.assertEqual([], errors)

    def test_multiplatform_byte_api_is_not_a_leak(self):
        self.write(COMMON, "\n".join((
            "import kotlin.uuid.Uuid",
            'val text = "hello".encodeToByteArray().decodeToString()',
            "val id = Uuid.random().toByteArray()",
            "val slice = buffer.toByteArray()",
            "val prefix = bytes.take(128).toByteArray().decodeToString()",
            "fun load(body: ByteReadPacket) = body.toByteArray().decodeToString()",
        )) + "\n")
        self.assert_clean()

    def test_clean_file_passes_and_reports_the_scan(self):
        self.assert_clean()
        self.assertIn("no JVM-only API leaks in 1 files", self.run_cli().stdout)

    def test_explicit_files_and_unreadable_input_fail_closed(self):
        scratch = self.write("scratch.kt", "import java.time.Clock\n")
        result = subprocess.run(["python3", str(CHECKER), str(scratch)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("scratch.kt:1:", result.stderr)
        result = subprocess.run(["python3", str(CHECKER), str(self.root / "gone.kt")],
                                capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
