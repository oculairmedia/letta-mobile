#!/usr/bin/env python3
"""Fake debugger/process fixtures; native evidence comes only from CI's AppImage."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

from desktop_smoke_fixtures import DesktopSmokeFixtures

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('startup_diagnostic',
                                            REPO / 'scripts/desktop/linux-startup-diagnostic.py')
diagnostic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostic)

TRACE = '\n'.join('TRACE ' + name + ' thread=1' for name in [
    'Java_sun_awt_X11_XlibWrapper_XSetInputFocus', 'XSetInputFocus', 'XSetErrorHandler', '_XDefaultError'])
FAILURE = 'X Error of failed request: BadMatch\nMajor opcode: 42 (X_SetInputFocus)'


class LinuxStartupDiagnosticTests(DesktopSmokeFixtures, unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.work = Path(temp.name)

    def run_trace_fixture(self, body, options=None):
        writer = self.home_writer()
        writer.write_text(writer.read_text().replace('#!/usr/bin/env python3', '#!' + os.sys.executable))
        fixture_options = {
            'tools': {
                # Preserve the supervisor's real three-second cleanup grace period.
                'sleep': 'exec /bin/sleep "$@"',
                'openbox': ('test "$XDG_CONFIG_DIRS" = /etc/xdg || exit 31\n'
                            'test "$XDG_DATA_DIRS" = /usr/local/share:/usr/share || exit 32\n'
                            'touch "$XDG_STATE_HOME/window-manager-ready"\n'
                            'exec /bin/sleep 120'),
                'xprop': ('test -f "$XDG_STATE_HOME/window-manager-ready" || exit 1\n'
                          "echo '_NET_SUPPORTING_WM_CHECK(WINDOW): window id # 0x200001'"),
                'python3': ('case "$1" in */inspect-linux-package.py) echo fixture-inspection;;\n'
                            f'*) exec "{os.sys.executable}" "$@";; esac'),
                'gdb': f'"{writer}" debugger\nprintf "%s\\n" "$@"\n' + body,
                **(options or {}).get('tools', {}),
            },
            'env': dict(LETTA_PACKAGE_X11_TRACE='1', APPDIR='/unrelated-app',
                        APPIMAGE='/unrelated-image', APPIMAGE_EXIT_AFTER_INSTALL='1',
                        **(options or {}).get('env', {})),
            'terminate_after': (options or {}).get('terminate_after'),
        }
        return self.run_window_harness_fixture(options=fixture_options)

    def test_complete_focus_trace_is_retained_from_isolated_same_image_replay(self):
        result, evidence = self.run_trace_fixture(f"cat <<'TRACE_END'\n{TRACE}\n{FAILURE}\nTRACE_END\n"
                                                  'test -z "${APPDIR+x}${APPIMAGE+x}${APPIMAGE_EXIT_AFTER_INSTALL+x}"\n')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((evidence / 'diagnostic-summary.txt').read_text().startswith('REPRODUCED:'))
        log = (evidence / 'gdb.log').read_text()
        self.assertIn('--args\n/bin/bash\n./diagnostic-apprun\n', log)
        self.assertIn('/relocated app/AppRun\n', log)
        self.assertIn('BadMatch', log)
        self.assertIn('window.AppImage', (evidence / 'artifact.sha256').read_text())
        self.assertEqual((evidence / 'gdb-status.txt').read_text(), '0\n')
        self.assert_home_isolated(['debugger'])

    def test_nonreproducing_debugger_is_inconclusive_even_when_it_exits_zero(self):
        result, evidence = self.run_trace_fixture('echo "visible window in debugger"\n')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        summary = (evidence / 'diagnostic-summary.txt').read_text()
        self.assertTrue(summary.startswith('INCONCLUSIVE:'), summary)
        self.assertIn('not a smoke pass', summary)

    def test_debugger_error_is_inconclusive_and_preserves_status(self):
        result, evidence = self.run_trace_fixture('echo debugger-error\nexit 17\n')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((evidence / 'gdb-status.txt').read_text(), '17\n')
        self.assertTrue((evidence / 'diagnostic-summary.txt').read_text().startswith('INCONCLUSIVE:'))

    def test_predebugger_failure_retains_inconclusive_label_and_failure(self):
        result, evidence = self.run_trace_fixture('exit 0', {'tools': {'openbox': 'exit 29'}})
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((evidence / 'diagnostic-summary.txt').read_text().startswith('INCONCLUSIVE:'))
        self.assertFalse((evidence / 'gdb.log').exists())

    def diagnostic_stubborn_payload(self):
        stubborn = self.stubborn_payload()
        source = stubborn.read_text().replace('child = os.fork()',
                                              'child = os.fork()\nif child == 0: os.setpgid(0, 0)')
        source = source.replace('status = work / "evidence/exit-status.txt"',
                                'status = pathlib.Path(os.environ["XDG_STATE_HOME"]).parent / "gdb-status.txt"')
        stubborn.write_text(source)
        return stubborn

    def test_timeout_kills_inferior_in_separate_group_and_keeps_unrelated_process(self):
        stubborn = self.diagnostic_stubborn_payload()
        unrelated = subprocess.Popen(['/bin/sleep', '120'])
        try:
            started = time.monotonic()
            result, evidence = self.run_trace_fixture(f'exec "{stubborn}"\n',
                                                       {'env': {'LETTA_X11_DIAGNOSTIC_TIMEOUT_SECONDS': '1'}})
            self.assertLess(time.monotonic() - started, 10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((evidence / 'gdb-status.txt').read_text(), '124\n')
            self.assertTrue((evidence / 'diagnostic-summary.txt').read_text().startswith('INCONCLUSIVE:'))
            self.assert_stubborn_stopped(124)
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.kill()
            unrelated.wait(timeout=2)

    def test_outer_term_cleans_separate_debugger_session_and_preserves_failure(self):
        stubborn = self.diagnostic_stubborn_payload()
        result, evidence = self.run_trace_fixture(f'exec "{stubborn}"\n',
                                                   {'terminate_after': self.work / 'child.pid'})
        self.assertEqual(result.returncode, 143, result.stdout + result.stderr)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '143\n')
        self.assert_stubborn_stopped('interrupted')

    def test_partial_trace_does_not_claim_reproduction(self):
        for missing in ['_XDefaultError', 'XSetErrorHandler', 'XSetInputFocus']:
            with self.subTest(missing=missing):
                trace = '\n'.join(line for line in TRACE.splitlines() if not line.startswith('TRACE ' + missing + ' '))
                self.assertTrue(diagnostic.diagnostic_summary(trace + '\n' + FAILURE).startswith('INCONCLUSIVE:'))

    def test_breakpoint_definitions_do_not_count_as_captured_events(self):
        commands = (REPO / 'scripts/desktop/linux-startup-diagnostic.gdb').read_text()
        self.assertTrue(diagnostic.diagnostic_summary(commands + FAILURE).startswith('INCONCLUSIVE:'))

    def test_second_jni_overload_can_establish_reproduction(self):
        trace = TRACE.replace('Java_sun_awt_X11_XlibWrapper_XSetInputFocus thread=',
                              'Java_sun_awt_X11_XlibWrapper_XSetInputFocus2 thread=')
        self.assertTrue(diagnostic.diagnostic_summary(trace + '\n' + FAILURE).startswith('REPRODUCED:'))

    def test_workflow_replay_cannot_replace_the_original_failure_or_rebuild(self):
        import yaml
        workflow = yaml.safe_load((REPO / '.github/workflows/desktop.yml').read_text())
        steps = workflow['jobs']['linux-desktop']['steps']
        original = next(step for step in steps if step.get('id') == 'linux-smoke')
        replay = next(step for step in steps if step.get('name') == 'Diagnose failed Linux startup with GDB')
        self.assertNotIn('continue-on-error', original)
        self.assertIn("steps.linux-smoke.outcome == 'failure'", replay['if'])
        self.assertIn('failure()', replay['if'])
        self.assertEqual(replay['env']['APPIMAGE_PATH'], '${{ steps.linux-smoke.outputs.appimage }}')
        self.assertEqual(replay['timeout-minutes'], 3)
        self.assertNotIn('gradlew', replay['run'])
        self.assertNotIn('|| true', original['run'])
        self.assertTrue(replay['continue-on-error'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
