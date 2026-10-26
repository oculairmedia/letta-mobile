"""Disposable homes and owned process fixtures for packaged desktop smoke tests."""
import os
from pathlib import Path
import signal
import subprocess
import time

REPO = Path(__file__).resolve().parents[2]


def write(path, data, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) or isinstance(data, bytearray) else data.encode())
    if executable:
        path.chmod(0o755)
    return path


class DesktopSmokeFixtures:
    def cleanup_fixture_children(self):
        for record in self.work.glob('*.pid'):
            try:
                os.kill(int(record.read_text()), signal.SIGKILL)
            except ProcessLookupError:
                pass

    def terminate_fixture_when_ready(self, process, ready):
        deadline = time.monotonic() + 5
        while not ready.exists():
            self.assertIsNone(process.poll(), 'Fixture exited before startup')
            self.assertLess(time.monotonic(), deadline, 'Fixture never reached startup')
            time.sleep(0.01)
        process.terminate()

    def stop_fixture_session(self, process, completed):
        # Only fixture-owned sessions/PIDs; never kill the caller's process group.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        if not completed:
            self.cleanup_fixture_children()
        process.communicate(timeout=2)

    def run_smoke_fixture(self, image, env, terminate_after=None):
        # A separate fixture session lets even the broken baseline be tested safely.
        process = subprocess.Popen(['bash', str(REPO / 'scripts/desktop/linux-package-smoke.sh'), str(image)],
                                   env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   start_new_session=True)
        self.addCleanup(self.cleanup_fixture_children)
        completed = False
        try:
            if terminate_after:
                self.terminate_fixture_when_ready(process, terminate_after)
            stdout, stderr = process.communicate(timeout=15)
            completed = True
            return subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)
        finally:
            self.stop_fixture_session(process, completed)

    def home_writer(self):
        return write(self.work / 'write-home', '#!/usr/bin/env python3\n'
                     'import json, os, pathlib, sys\n'
                     'keys = ["HOME", "USERPROFILE", "LETTA_HOME", "APPDATA", "LOCALAPPDATA", '
                     '"XDG_CONFIG_DIRS", "XDG_DATA_DIRS", "XDG_CONFIG_HOME", '
                     '"XDG_CACHE_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR"]\n'
                     'paths = {key: os.environ[key] for key in keys}\n'
                     'for value in paths.values():\n'
                     '    p = pathlib.Path(value) / "fixture-settings"; p.parent.mkdir(parents=True, exist_ok=True)\n'
                     '    p.write_text("mutated")\n'
                     'with open(os.environ["FIXTURE_OBSERVATIONS"], "a") as log:\n'
                     '    log.write(json.dumps(dict(phase=sys.argv[1:], paths=paths, '
                     'cwd=os.getcwd(), java=os.environ.get("JAVA_TOOL_OPTIONS"), overrides=[key for key in '
                     '["_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"] if key in os.environ])) + "\\n")\n', True)

    def isolated_fixture_env(self, **extra):
        real_home = self.work / 'caller-home'
        real_home.mkdir(exist_ok=True)
        write(real_home / 'fixture-settings', 'original user settings')
        env = dict(os.environ, **{key: str(real_home) for key in [
            'HOME', 'USERPROFILE', 'LETTA_HOME', 'APPDATA', 'LOCALAPPDATA', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME',
            'XDG_CONFIG_DIRS', 'XDG_DATA_DIRS',
            'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_RUNTIME_DIR']})
        temp_base = self.work / 'temp with spaces'
        temp_base.mkdir(exist_ok=True)
        env.update(TMPDIR=str(temp_base), FIXTURE_OBSERVATIONS=str(self.work / 'observations.jsonl'),
                   JAVA_TOOL_OPTIONS=f'-Duser.home={real_home}', _JAVA_OPTIONS=f'-Duser.home={real_home}',
                   JDK_JAVA_OPTIONS=f'-Duser.home={real_home}', **extra)
        return env

    def assert_home_isolated(self, expected_phases):
        import json
        observations = [json.loads(line) for line in (self.work / 'observations.jsonl').read_text().splitlines()]
        self.assertEqual((self.work / 'caller-home/fixture-settings').read_text(), 'original user settings')
        self.assertEqual([o['phase'][0] for o in observations], expected_phases)
        for observation in observations:
            for value in observation['paths'].values():
                self.assertTrue(Path(value).is_relative_to(self.work), value)
                self.assertNotEqual(value, str(self.work / 'caller-home'))
            self.assertIn(f'-Duser.home={observation["paths"]["HOME"]}', observation['java'])
            self.assertEqual(observation['overrides'], [])
            self.assertTrue(Path(observation['cwd']).is_relative_to(self.work), observation['cwd'])

    def run_window_harness_fixture(self, startup='', crash=False, visible=True, options=None):
        # These simulate process/window boundaries, never count as native launch evidence.
        options = options or {}
        write_home = options.get('write_home', False)
        ready = options.get('ready')
        tools = self.work / 'window-tools'
        for name, body in {
            'python3': 'echo fixture-payload-inspection',
            'desktop-file-validate': 'exit 0',
            'openbox': 'exec /bin/sleep 120',
            'xprop': "echo '_NET_SUPPORTING_WM_CHECK(WINDOW): window id # 0x200001'",
            'xwininfo': 'echo fixture-window-tree',
            'sleep': 'exec /bin/sleep 0.01',
            'xdotool': ('if [ -n "$FIXTURE_READY" ]; then\n'
                        'while [ ! -f "$FIXTURE_READY" ]; do /bin/sleep 0.01; done\nfi\n' +
                        ('if [ "$1" = search ]; then echo 123; else echo Meridian; fi' if visible else 'exit 1')),
            **options.get('tools', {}),
        }.items():
            write(tools / name, '#!/bin/sh\n' + body + '\n', True)
        writer = self.home_writer()
        if write_home:
            # Use the actual interpreter for this fixture, not its inspector shim.
            writer.write_text(writer.read_text().replace('#!/usr/bin/env python3', '#!' + os.sys.executable))
            write(tools / 'python3', f'#!/bin/sh\n"{writer}" inspector\necho fixture-payload-inspection\n', True)
        payload = self.work / 'window-payload'
        write(payload / 'fixture.desktop', '[Desktop Entry]\n')
        write(payload / 'AppRun', '#!/bin/sh\n' + (f'"{writer}" app\n' if write_home else '') + startup + '\n' +
              ('touch hs_err_pid123.log\n' if crash else '') + 'exec /bin/sleep 120\n', True)
        image = write(self.work / 'window.AppImage', '#!/bin/sh\n' + (f'"{writer}" extract\n' if write_home else '') + 'mkdir squashfs-root\n'
                      'cp -a "$FIXTURE_PAYLOAD/." squashfs-root/\n', True)
        evidence = self.work / 'evidence'
        env = self.isolated_fixture_env(FIXTURE_PAYLOAD=str(payload),
                   FIXTURE_READY=str(ready) if ready else '',
                   PATH=str(tools) + os.pathsep + os.environ['PATH'], DISPLAY=':fixture',
                   DBUS_SESSION_BUS_ADDRESS='fixture-only', LETTA_PACKAGE_SMOKE_EVIDENCE=str(evidence),
                   **options.get('env', {}))
        result = self.run_smoke_fixture(image, env, terminate_after=options.get('terminate_after'))
        return result, evidence

    def stubborn_payload(self):
        # Parent and child both ignore TERM. The handler also proves evidence was copied first.
        return write(self.work / 'stubborn', '#!' + os.sys.executable + '\n'
                     'import os, pathlib, signal, time\n'
                     f'work = pathlib.Path({str(self.work)!r})\n'
                     'def term(*_):\n'
                     '    status = work / "evidence/exit-status.txt"\n'
                     '    (work / (str(os.getpid()) + ".term")).write_text(status.read_text() if status.exists() else "MISSING")\n'
                     'signal.signal(signal.SIGTERM, term)\n'
                     'child = os.fork()\n'
                     '(work / ("child.pid" if child == 0 else "parent.pid")).write_text(str(os.getpid()))\n'
                     'while True: time.sleep(0.01)\n', True)

    def assert_stubborn_stopped(self, status):
        self.assertTrue((self.work / 'parent.pid').exists())
        self.assertTrue((self.work / 'child.pid').exists())
        for record in self.work.glob('*.pid'):
            pid = int(record.read_text())
            stat = Path(f'/proc/{pid}/stat')
            self.assertTrue(not stat.exists() or stat.read_text().split(') ')[1].startswith('Z'),
                            f'Fixture process {pid} survived cleanup')
            self.assertEqual((self.work / f'{pid}.term').read_text(), f'{status}\n')
