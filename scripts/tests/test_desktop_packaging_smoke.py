"""Desktop packaging regression tests: smoke."""
import os
import subprocess
import time
import unittest

from desktop_packaging_fixtures import LinuxPackageFixtures, REPO, write


class LinuxPackageSmokeTests(LinuxPackageFixtures, unittest.TestCase):
    def run_titled_window_fixture(self, title='Home', foreign_pid=False, window_type='NORMAL'):
        owner = self.work / 'window-owner.txt'
        search = f'''exec "{os.sys.executable}" - "$@" <<'PYTHON'
from pathlib import Path
import re
import sys
args = sys.argv[1:]
title = {title!r}
if args[0] == 'getwindowname':
    print(title)
    sys.exit(0)
owner = Path({str(owner)!r})
if not owner.exists():
    sys.exit(1)
window_pid = '1' if {foreign_pid!r} else owner.read_text().strip()
if '--pid' in args and args[args.index('--pid') + 1] != window_pid:
    sys.exit(1)
if '--name' in args and not re.search(args[args.index('--name') + 1], title):
    sys.exit(1)
print(123)
PYTHON'''
        properties = ('if [ "$1" = -root ]; then\n'
                      "echo '_NET_SUPPORTING_WM_CHECK(WINDOW): window id # 0x200001'\n"
                      f"else echo '_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_{window_type}'; fi")
        return self.run_window_harness_fixture(startup=f'echo $$ > "{owner}"',
                                               options={'tools': {'xdotool': search, 'xprop': properties}})


    def test_smoke_failure_retains_logs_and_exit_status(self):
        image = write(self.work / 'broken.AppImage', '#!/bin/sh\necho extraction-failed\nexit 23\n', True)
        env = dict(os.environ, TMPDIR=str(self.work))
        result = subprocess.run(['bash', str(REPO / 'scripts/desktop/linux-package-smoke.sh'), str(image)],
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 23)
        retained = list(self.work.glob('letta-linux-package-smoke.*'))
        self.assertEqual(len(retained), 1)
        self.assertEqual((retained[0] / 'extract.log').read_text(), 'extraction-failed\n')
        self.assertIn(f'Smoke evidence retained: {retained[0]} (exit 23)', result.stderr)


    def test_every_payload_phase_uses_disposable_home(self):
        # A simulated visible window must not precede the app's HOME-writing startup phase.
        ready = self.work / 'home-phase-ready'
        result, _ = self.run_window_harness_fixture(
            startup='touch "$FIXTURE_READY"', options={'write_home': True, 'ready': ready},
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_home_isolated(['extract', 'inspector', 'app'])


    def test_cleanup_kills_stubborn_descendants_and_keeps_unrelated_process(self):
        stubborn = self.stubborn_payload()
        # This control deliberately shares the smoke caller's process group.
        unrelated = subprocess.Popen(['/bin/sleep', '120'])
        try:
            start = time.monotonic()
            result, evidence = self.run_window_harness_fixture(startup=f'exec "{stubborn}"',
                                                              options={'ready': self.work / 'child.pid'})
            self.assertLess(time.monotonic() - start, 8)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((evidence / 'window.txt').read_text(), 'Meridian\n')
            self.assert_stubborn_stopped(0)
            self.assertIsNone(unrelated.poll(), 'Cleanup killed an unrelated caller process')
        finally:
            unrelated.kill()
            unrelated.wait(timeout=2)


    def test_extraction_failure_preserves_status_and_evidence_before_cleanup(self):
        stubborn = self.stubborn_payload()
        image = write(self.work / 'broken.AppImage', '#!/bin/sh\n'
                      f'"{stubborn}" &\n'
                      f'while [ ! -f "{self.work}/child.pid" ]; do /bin/sleep 0.01; done\n'
                      'echo extraction-failed\nexit 23\n', True)
        evidence = self.work / 'evidence'
        env = self.isolated_fixture_env(LETTA_PACKAGE_SMOKE_EVIDENCE=str(evidence))
        start = time.monotonic()
        result = self.run_smoke_fixture(image, env)
        self.assertLess(time.monotonic() - start, 8)
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '23\n')
        self.assertEqual((evidence / 'extract.log').read_text(), 'extraction-failed\n')
        self.assert_stubborn_stopped(23)


    def test_term_preserves_signal_status_and_cleans_owned_children(self):
        stubborn = self.stubborn_payload()
        result, evidence = self.run_window_harness_fixture(startup=f'exec "{stubborn}"', visible=False,
                                                          options={'terminate_after': self.work / 'child.pid'})
        self.assertEqual(result.returncode, 143, result.stdout + result.stderr)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '143\n')
        self.assert_stubborn_stopped(143)


    def test_evidence_copy_error_does_not_replace_payload_failure_status(self):
        image = write(self.work / 'broken.AppImage', '#!/bin/sh\necho extraction-failed\nexit 23\n', True)
        evidence = write(self.work / 'evidence-is-a-file', 'fixture')
        env = self.isolated_fixture_env(LETTA_PACKAGE_SMOKE_EVIDENCE=str(evidence))
        result = self.run_smoke_fixture(image, env)
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)


    def test_window_harness_records_success_only_after_visible_window(self):
        result, evidence = self.run_window_harness_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '0\n')
        self.assertEqual((evidence / 'window.txt').read_text(), 'Meridian\n')
        self.assertEqual(list(self.work.rglob('letta-linux-package-smoke.*')), [])


    def test_window_harness_retains_nonzero_child_exit_status(self):
        result, evidence = self.run_window_harness_fixture(startup='exit 17', options={'tools': {'xdotool': 'exit 1'}})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((evidence / 'app-exit-status.txt').read_text(), '17\n')


    def test_window_harness_rejects_clean_exit_before_window(self):
        result, evidence = self.run_window_harness_fixture(startup='exit 0', options={'tools': {'xdotool': 'exit 1'}})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((evidence / 'app-exit-status.txt').read_text(), '0\n')


    def test_window_harness_accepts_dynamic_destination_title(self):
        result, evidence = self.run_titled_window_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((evidence / 'window.txt').read_text(), 'Home\n')


    def test_window_harness_rejects_foreign_pid_with_brand_title(self):
        result, evidence = self.run_titled_window_fixture(title='Meridian Desktop', foreign_pid=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '1\n')


    def test_window_harness_rejects_dialog_instead_of_normal_frame(self):
        result, evidence = self.run_titled_window_fixture(window_type='DIALOG')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '1\n')


    def test_window_harness_rejects_x_error_even_with_a_window_and_live_process(self):
        result, evidence = self.run_window_harness_fixture(startup="echo 'X Error of failed request: BadMatch'")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BadMatch', (evidence / 'startup.log').read_text())
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '1\n')


    def test_window_harness_rejects_new_jvm_crash_log(self):
        result, evidence = self.run_window_harness_fixture(crash=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('New crash evidence', result.stderr)
        self.assertTrue((evidence / 'hs_err_pid123.log').is_file())


    def test_window_harness_rejects_alive_process_without_a_window(self):
        result, evidence = self.run_window_harness_fixture(visible=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('No visible desktop window', result.stderr)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '1\n')
        self.assertIn('Packaged app PID:', (evidence / 'window-tree.txt').read_text())
        self.assertIn('fixture-window-tree', (evidence / 'window-tree.txt').read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
