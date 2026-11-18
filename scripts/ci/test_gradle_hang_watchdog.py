"""Tests for gradle-hang-watchdog.sh: run with python3 -m unittest discover -s scripts/ci -p 'test_gradle_hang_watchdog.py'.

Needs Linux procps (`ps -eo`), as on the ubuntu CI runners.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

WATCHDOG = Path(os.environ.get("WATCHDOG_SCRIPT", Path(__file__).with_name("gradle-hang-watchdog.sh")))
SENTINEL = "--sentinel-not-a-secret-7f3a"


def _procps_available():
    if not sys.platform.startswith("linux") or shutil.which("bash") is None:
        return False
    return subprocess.run(["ps", "-eo", "pid,comm"], capture_output=True).returncode == 0


@unittest.skipUnless(_procps_available(), "needs Linux procps ps")
class GradleHangWatchdogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        # Named "chrome" so the fixture lands in the Chrome and Karma section by
        # executable name, and carries the sentinel only in its argv.
        fake_chrome = root / "chrome"
        fake_chrome.symlink_to(Path(sys.executable).resolve())
        self.fixture = subprocess.Popen(
            [str(fake_chrome), "-c", "import time; time.sleep(120)", SENTINEL]
        )
        self.output = root / "out" / "diagnostics.txt"

    def tearDown(self):
        self.fixture.kill()
        self.fixture.wait()
        self.tmp.cleanup()

    def test_fixture_identified_without_argv(self):
        subprocess.run(["bash", str(WATCHDOG), "0", str(self.output)], check=True, timeout=120)
        text = self.output.read_text()

        self.assertNotIn(SENTINEL, text)
        self.assertNotIn("sentinel-not-a-secret", text)

        chrome_section = text.split("=== Chrome and Karma ===", 1)[1].split("=== Java thread dumps ===", 1)[0]
        fixture_rows = [
            row.split() for row in chrome_section.splitlines() if row.split()[:1] == [str(self.fixture.pid)]
        ]
        self.assertEqual(len(fixture_rows), 1, chrome_section)
        self.assertEqual(fixture_rows[0][-1], "chrome")
        self.assertIn("  parent: ", chrome_section)
        self.assertIn(str(os.getpid()), chrome_section)


if __name__ == "__main__":
    unittest.main()
