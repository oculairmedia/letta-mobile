"""Regression cases for the source-derived package overview CI contract."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ci/check-api-package-coverage.py'
SPEC = importlib.util.spec_from_file_location('package_coverage', SCRIPT)
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)
PROSE = 'Owns the shared example contract and accepts adapters through its public interface.'


class PackageCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / 'android-compose/example'
        self.write('android-compose/build.gradle.kts', 'dependencies { add("dokka", project(":example")) }')
        self.write('android-compose/Module.md', '# Module Letta Mobile API\n\n' + PROSE)
        self.overview('# Package example\n\n' + PROSE)
        self.source('commonMain', 'package example\nclass Example')

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        return path

    def source(self, source_set, text, name='Example.kt'):
        return self.write(f'android-compose/example/src/{source_set}/kotlin/{name}', text)

    def overview(self, text):
        return self.write('android-compose/example/Module.md', '# Module :example\n\n' + PROSE + '\n\n' + text)

    def problems(self):
        return '\n'.join(coverage.check(self.root)[1])

    def test_valid_and_platform_duplicates(self):
        self.source('jvmAndAndroid', 'package example\nclass Platform')
        self.source('wasmJsMain', 'package example\nclass Browser')
        self.assertEqual((1, []), coverage.check(self.root))

    def test_new_package_requires_section(self):
        self.source('commonMain', 'package example.new\nclass Added', 'Added.kt')
        self.assertIn('missing # Package example.new', self.problems())

    def test_removed_package_rejects_orphan(self):
        self.source('commonMain', 'package replacement\nclass Example')
        self.assertIn('stale package example', self.problems())
        self.assertIn('missing # Package replacement', self.problems())

    def test_duplicate_section_rejected(self):
        self.overview(('# Package example\n\n' + PROSE + '\n\n') * 2)
        self.assertIn('duplicate Package example', self.problems())

    def test_fenced_and_commented_headings_cannot_supply_coverage(self):
        for fake in ['```markdown\n# Package example\n' + PROSE + '\n```',
                     '~~~\n# Package example\n' + PROSE + '\n~~~',
                     '<!--\n# Package example\n' + PROSE + '\n-->']:
            with self.subTest(fake=fake):
                self.overview(fake)
                self.assertIn('missing # Package example', self.problems())

    def test_examples_do_not_create_orphans(self):
        self.overview('# Package example\n\n' + PROSE + '\n\n```\n# Package fake\n```')
        self.assertEqual('', self.problems())

    def test_empty_and_wrong_module_fail(self):
        self.overview('# Package example\n')
        self.assertIn('introductory paragraph', self.problems())
        self.overview('# Package example\n\n' + PROSE + '\n# Module :wrong\n\n' + PROSE)
        self.assertIn('unexpected # Module :wrong', self.problems())

    def test_comments_and_strings_do_not_invent_packages(self):
        self.source('commonMain', '/* outer /* nested */\npackage fake\n*/\npackage example\nval s = """\npackage fake\n"""')
        self.assertEqual('', self.problems())

    def test_tests_resources_and_build_outputs_excluded(self):
        for source_set in ['commonTest', 'jvmAndAndroidTest', 'androidInstrumentedTest', 'test']:
            self.source(source_set, 'package tests.only\nclass Test')
        self.write('android-compose/example/src/commonMain/resources/example.kt', 'package resource')
        self.write('android-compose/example/build/generated/G.kt', 'package generated')
        self.assertEqual((1, []), coverage.check(self.root))

    def test_plain_jvm_and_java_sources(self):
        self.source('main', 'package example; class Java {}', 'Example.java')
        self.assertEqual('', self.problems())

    def test_custom_source_roots_fail_closed(self):
        self.write('android-compose/example/build.gradle.kts', 'kotlin.srcDir("extra")')
        self.assertIn('custom source roots', self.problems())

    def test_unknown_source_set_fails_closed(self):
        self.source('newIntermediate', 'package example\nclass Intermediate')
        self.assertIn('unrecognized production source set', self.problems())

    def test_missing_package_is_not_silently_ignored(self):
        self.source('commonMain', 'class NoPackage')
        self.assertIn('expected one named package', self.problems())

    def test_commented_gradle_dependencies_ignored(self):
        self.write('android-compose/build.gradle.kts', '// add("dokka", project(":fake"))\n/* nested /* x */ add("dokka", project(":fake")) */\nadd(\n"dokka", project( ":example" )\n)')
        self.assertEqual([':example'], coverage.published_modules(self.root))

    def test_partial_or_empty_discovery_fails_closed(self):
        for build in ['', 'add("dokka", project(":example"))\nadd("dokka", project(other))',
                      'dokka(project(":example"))']:
            with self.subTest(build=build):
                self.write('android-compose/build.gradle.kts', build)
                with self.assertRaisesRegex(ValueError, 'Unsupported Dokka'):
                    coverage.published_modules(self.root)

    def test_missing_overview_and_source_root_fail(self):
        (self.module / 'Module.md').unlink()
        self.assertTrue(self.problems())
        self.overview('# Package example\n\n' + PROSE)
        (self.module / 'src/commonMain/kotlin/Example.kt').unlink()
        self.assertIn('stale package example', self.problems())

    def test_cli_returns_nonzero_for_drift(self):
        self.source('commonMain', 'package changed\nclass Changed')
        result = subprocess.run([sys.executable, str(SCRIPT), '--root', str(self.root)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn('missing # Package changed', result.stderr)


if __name__ == '__main__':
    unittest.main()
