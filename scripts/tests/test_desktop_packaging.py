#!/usr/bin/env python3
"""No Gradle/native builds: real staging, tar-mode and rejected-payload regression fixtures."""
import importlib.util
import hashlib
import io
import os
from pathlib import Path
import signal
import struct
import subprocess
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'scripts/desktop/linux-runtime.sh'
spec = importlib.util.spec_from_file_location('package_inspector', REPO / 'scripts/desktop/inspect-linux-package.py')
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)


def elf(machine=62):
    data = bytearray(64)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, machine)
    return data


def write(path, data, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data if isinstance(data, bytes) or isinstance(data, bytearray) else data.encode())
    if executable:
        path.chmod(0o755)
    return path


class LinuxPackagingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.root = self.work / 'app with spaces'
        self.runtime = self.root / 'lib/app/resources/letta-code-runtime'
        self.node = write(self.runtime / 'node', elf(), True)
        write(self.runtime / 'runtime-manifest.json', '{"node":"24.13.1","lettaCode":"0.34.14"}')
        write(self.runtime / 'node_modules/@letta-ai/letta-code/letta.js', '// fixture')
        write(self.runtime / 'node_modules/@letta-ai/letta-code/package.json', '{"version":"0.34.14"}')
        self.cfg = write(self.root / 'lib/app/Meridian Desktop.cfg',
                         'app.mainclass=com.letta.mobile.desktop.MainKt\napp.classpath=$APPDIR/natives.jar\n'
                         'java-options=-Djpackage.app-version=0.26.0\n'
                         'java-options=-Dnucleus.app.id=Meridian Desktop\n'
                         'java-options=-Dnucleus.app.id=ca.oculair.meridian.desktop\n'
                         '-Dletta.desktop.runtime.node=$APPDIR/resources/letta-code-runtime/node\n'
                         '-Dletta.desktop.runtime.lettaJs=$APPDIR/resources/letta-code-runtime/node_modules/@letta-ai/letta-code/letta.js\n')
        write(self.root / 'lib/runtime/lib/server/libjvm.so', elf())
        write(self.root / 'bin/Meridian Desktop', elf(), True)
        write(self.root / 'lib/runtime/release', 'JAVA_VERSION="25.0.4"\nOS_ARCH="x86_64"\n')
        write(self.root / 'AppRun', '#!/bin/sh\n', True)
        write(self.root / 'meridian.desktop', '[Desktop Entry]\nType=Application\nName=Meridian\nExec=meridian %U\nIcon=meridian\nX-AppImage-Version=0.26.0\n')
        write(self.root / 'meridian.png', b'fixture')
        for name in ('libappindicator3.so.1', 'libindicator3.so.7'):
            write(self.root / 'usr/lib' / name, elf())
        self.jar = self.root / 'lib/app/natives.jar'
        self.jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(self.jar, 'w') as z:
            for name in ['native/x86_64-unknown-linux-gnu/libautomerge_jni.so',
                         'linux-x86-64/libiroh_ffi.so', 'libletta_mermaid_renderer.so', 'libletta_tablet_input.so']:
                z.writestr(name, elf())
            # Cross-platform upstream JAR members are allowed and never selected as Linux JNI.
            z.writestr('native/aarch64-unknown-linux-gnu/libautomerge_jni.so', elf(183))
            z.writestr('win32-x86-64/iroh_ffi.dll', b'MZ')

    def inspect(self):
        return inspector.inspect(self.root, self.work / 'native')

    def test_complete_relocated_package_and_universal_jars(self):
        layout = self.inspect()
        self.assertEqual(len(layout['jni']), 4)
        self.assertEqual(layout['node'], str(self.node))

    def test_missing_execute_bit_is_rejected(self):
        self.node.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'not executable'):
            self.inspect()

    def test_legacy_gtk2_toolset_is_rejected(self):
        write(self.root / 'usr/lib/libindicator.so.7', elf())
        with self.assertRaisesRegex(ValueError, 'Legacy GTK2'):
            self.inspect()

    def test_missing_gtk3_indicator_is_rejected(self):
        (self.root / 'usr/lib/libappindicator3.so.1').unlink()
        with self.assertRaisesRegex(ValueError, 'GTK3 AppImage library missing'):
            self.inspect()

    def test_wrong_node_architecture_is_rejected(self):
        self.node.write_bytes(elf(183))
        with self.assertRaisesRegex(ValueError, 'Not x86-64'):
            self.inspect()

    def test_jlinked_release_without_arch_metadata_uses_actual_jvm(self):
        write(self.root / 'lib/runtime/release', 'JAVA_VERSION="25.0.4"\n')
        self.inspect()

    def test_wrong_jvm_architecture_is_rejected(self):
        write(self.root / 'lib/runtime/lib/server/libjvm.so', elf(183))
        with self.assertRaisesRegex(ValueError, 'Not x86-64'):
            self.inspect()

    def test_missing_appserver_is_rejected(self):
        (self.runtime / 'node_modules/@letta-ai/letta-code/letta.js').unlink()
        with self.assertRaisesRegex(ValueError, 'AppServer entry missing'):
            self.inspect()

    def test_old_java_is_rejected(self):
        write(self.root / 'lib/runtime/release', 'JAVA_VERSION="21.0.10"\nOS_ARCH="x86_64"')
        with self.assertRaisesRegex(ValueError, 'Java 25'):
            self.inspect()

    def test_missing_jni_is_rejected(self):
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('libletta_mermaid_renderer.so', elf())
        with self.assertRaisesRegex(ValueError, 'Missing Linux JNI'):
            self.inspect()

    def test_foreign_loose_native_is_rejected(self):
        write(self.runtime / 'node_modules/foreign/binary.exe', b'MZ')
        with self.assertRaisesRegex(ValueError, 'Foreign or invalid'):
            self.inspect()

    def test_foreign_sharp_is_rejected_even_when_empty(self):
        (self.runtime / 'node_modules/@img/sharp-win32-x64').mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'Foreign sharp'):
            self.inspect()

    def test_build_path_binding_is_rejected(self):
        self.cfg.write_text(self.cfg.read_text().replace('$APPDIR', '/home/runner/work/build'))
        with self.assertRaisesRegex(ValueError, 'relocatable'):
            self.inspect()

    def test_stale_outer_appimage_version_is_rejected(self):
        entry = self.root / 'meridian.desktop'
        entry.write_text(entry.read_text().replace('0.26.0', '0.25.0'))
        with self.assertRaisesRegex(ValueError, 'AppImage version does not match'):
            self.inspect()

    def test_display_name_cannot_be_used_as_linux_app_id(self):
        self.cfg.write_text(self.cfg.read_text().replace(
            'java-options=-Dnucleus.app.id=ca.oculair.meridian.desktop\n', ''))
        with self.assertRaisesRegex(ValueError, 'valid MPRIS D-Bus name'):
            self.inspect()

    def test_external_symlink_is_rejected(self):
        (self.root / 'external').symlink_to(self.work / 'outside')
        with self.assertRaisesRegex(ValueError, 'External/broken symlink'):
            self.inspect()

    def test_tar_extraction_preserves_execute_bits_and_links(self):
        archive = self.work / 'runtime.tar.gz'
        with tarfile.open(archive, 'w:gz') as tar:
            info = tarfile.TarInfo('upstream/bin/node')
            info.mode = 0o755
            info.size = 4
            tar.addfile(info, io.BytesIO(b'node'))
            link = tarfile.TarInfo('upstream/bin/node-link')
            link.type = tarfile.SYMTYPE
            link.linkname = 'node'
            tar.addfile(link)
        dest = self.work / 'extracted with spaces'
        subprocess.run(['bash', str(SCRIPT), 'extract', str(archive), str(dest)], check=True)
        self.assertTrue(os.access(dest / 'bin/node', os.X_OK))
        self.assertEqual((dest / 'bin/node-link').readlink(), Path('node'))

    def appimage_tools_archive(self, gtk3=True):
        source = self.work / 'upstream-tools'
        for name in ('mksquashfs', 'desktop-file-validate'):
            write(source / name, '#!/bin/sh\nprintf "%s\\n" "$0" "$@"\n', True)
        # Upstream stores the runtime as non-executable data; the builder prepends it.
        write(source / 'runtimes/runtime-x64', elf())
        for name in ('libnotify.so.4', 'libXss.so.1', 'libXtst.so.6', 'libgconf-2.so.4'):
            write(source / 'lib/x64' / name, elf())
        if gtk3:
            for name in ('libappindicator3.so.1', 'libindicator3.so.7'):
                write(source / 'lib/x64' / (name + '.0'), elf())
                (source / 'lib/x64' / name).symlink_to(name + '.0')
        archive = self.work / 'tools.tar.gz'
        with tarfile.open(archive, 'w:gz') as tar:
            tar.add(source, arcname='.')
        return archive

    def test_appimage_tool_override_preserves_gtk3_set_links_and_tool_arguments(self):
        archive = self.appimage_tools_archive()
        dest = self.work / 'tool override with spaces'
        subprocess.run(['bash', str(SCRIPT), 'appimage-tools', str(archive), str(dest)], check=True)
        for name in ('libappindicator3.so.1', 'libindicator3.so.7'):
            self.assertTrue((dest / 'lib/x64' / name).is_symlink())
            self.assertEqual((dest / 'lib/x64' / name).read_bytes(), elf())
        self.assertEqual(len(list((dest / 'lib/x64').iterdir())), 8)
        self.assertEqual((dest / 'runtime-x64').read_bytes(), elf())
        for name in ('mksquashfs', 'desktop-file-validate'):
            output = subprocess.check_output([str(dest / 'linux-x64' / name), 'argument with spaces'], text=True)
            self.assertEqual(Path(output.splitlines()[0]).resolve(), dest / name)
            self.assertEqual(output.splitlines()[1:], ['argument with spaces'])

    def test_appimage_tool_override_rejects_incomplete_gtk3_without_replacing_cache(self):
        archive = self.appimage_tools_archive(gtk3=False)
        dest = self.work / 'cached-tools'
        sentinel = write(dest / 'sentinel', 'old cache')
        result = subprocess.run(['bash', str(SCRIPT), 'appimage-tools', str(archive), str(dest)],
                                text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('GTK3 AppImage library missing', result.stderr)
        self.assertEqual(sentinel.read_text(), 'old cache')
        self.assertEqual(list(self.work.glob('cached-tools.*')), [])

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

    def test_real_stager_keeps_linux_sharp_electron_and_prunes_other_platforms(self):
        install = self.work / 'install'
        modules = install / 'node_modules'
        write(modules / '@letta-ai/letta-code/letta.js', '// fixture')
        write(modules / '@letta-ai/letta-code/node_modules/@janhapke/sharp-electron/linux-x64/sharp.node', elf())
        for name in ['sharp-linux-x64', 'sharp-libvips-linux-x64', 'sharp-win32-x64', 'sharp-linuxmusl-x64']:
            write(modules / '@img' / name / 'package.json', '{}')
        write(modules / '@img/sharp-linux-x64/lib/sharp-linux-x64.node', elf())
        write(modules / '@img/sharp-libvips-linux-x64/lib/libvips-cpp.so.8', elf())
        write(modules / '@vscode/ripgrep-win32-x64/package.json', '{"os":["win32"],"cpu":["x64"]}')
        write(modules / '@vscode/ripgrep-linux-x64/package.json', '{"os":["linux"],"cpu":["x64"]}')
        write(modules / '.bin/fixture', '#!/bin/sh\n', True)
        for name in ['package.json', 'package-lock.json', 'runtime-manifest.json']:
            write(install / name, '{}')
        dest = self.work / 'staged'
        subprocess.run(['bash', str(SCRIPT), 'stage', str(self.node), str(install), str(dest)], check=True)
        self.assertTrue(os.access(dest / 'node', os.X_OK))
        self.assertTrue(os.access(dest / 'node_modules/.bin/fixture', os.X_OK))
        self.assertTrue((dest / 'node_modules/@letta-ai/letta-code/node_modules/@janhapke/sharp-electron/linux-x64/sharp.node').exists())
        self.assertFalse((dest / 'node_modules/@img/sharp-win32-x64').exists())
        self.assertFalse((dest / 'node_modules/@img/sharp-linuxmusl-x64').exists())
        self.assertFalse((dest / 'node_modules/@vscode/ripgrep-win32-x64').exists())
        self.assertTrue((dest / 'node_modules/@vscode/ripgrep-linux-x64').exists())
        self.assertTrue((dest / 'node_modules/@img/sharp-linux-x64/lib/sharp-linux-x64.node').is_file())
        self.assertTrue((dest / 'node_modules/@img/sharp-libvips-linux-x64/lib/libvips-cpp.so.8').is_file())

    def test_download_rejects_bad_digest_without_replacing_cached_archive(self):
        shim = self.work / 'tools'
        # Fake only transport; the real helper still computes and enforces the digest.
        write(shim / 'curl', '#!/bin/bash\nwhile [[ $# -gt 0 ]]; do\n'
              'if [[ $1 == -o ]]; then printf downloaded > "$2"; exit 0; fi\nshift\ndone\nexit 1\n', True)
        dest = write(self.work / 'cached.tar.gz', 'old cache')
        env = dict(os.environ, PATH=str(shim) + os.pathsep + os.environ['PATH'])
        result = subprocess.run(['bash', str(SCRIPT), 'download', 'https://fixture.invalid/archive',
                                 str(dest), '256', hashlib.sha256(b'correct upstream').hexdigest()],
                                env=env, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Checksum mismatch', result.stderr)
        self.assertEqual(dest.read_text(), 'old cache')
        self.assertEqual(list(self.work.glob('cached.tar.gz.*')), [])
        result = subprocess.run(['bash', str(SCRIPT), 'download', 'https://fixture.invalid/archive',
                                 str(dest), '256', hashlib.sha256(b'downloaded').hexdigest()], env=env)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(dest.read_text(), 'downloaded')

    def test_stager_prunes_foreign_node_pty_binaries_but_keeps_linux_build(self):
        install = self.work / 'install'
        modules = install / 'node_modules'
        write(modules / '@letta-ai/letta-code/letta.js', '// fixture')
        write(modules / '@img/sharp-linux-x64/package.json', '{}')
        pty = modules / '@letta-ai/letta-code/node_modules/node-pty'
        write(pty / 'package.json', '{"name":"node-pty"}')
        write(pty / 'build/Release/pty.node', elf(), True)
        for platform in ['win32-x64', 'win32-arm64', 'darwin-x64', 'darwin-arm64']:
            write(pty / 'prebuilds' / platform / 'pty.node', b'foreign')
        write(pty / 'third_party/conpty/win10-x64/OpenConsole.exe', b'MZ')
        for name in ['package.json', 'package-lock.json', 'runtime-manifest.json']:
            write(install / name, '{}')
        dest = self.work / 'staged'
        subprocess.run(['bash', str(SCRIPT), 'stage', str(self.node), str(install), str(dest)], check=True)
        staged = dest / pty.relative_to(install)
        self.assertTrue(os.access(staged / 'build/Release/pty.node', os.X_OK))
        self.assertFalse((staged / 'prebuilds/win32-x64').exists())
        self.assertFalse((staged / 'prebuilds/win32-arm64').exists())
        self.assertFalse((staged / 'prebuilds/darwin-x64').exists())
        self.assertFalse((staged / 'prebuilds/darwin-arm64').exists())
        self.assertFalse((staged / 'third_party/conpty').exists())

    def test_selected_linux_jni_with_wrong_architecture_is_rejected(self):
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('linux-x86-64/libiroh_ffi.so', elf(183))
        with self.assertRaisesRegex(ValueError, 'Not x86-64'):
            self.inspect()

    def test_host_mapping_and_existing_windows_contract_are_retained(self):
        build = (REPO / 'android-compose/desktop/build.gradle.kts').read_text()
        for expected in ['if (isWindowsHost) "win-x64" else "linux-x64"',
                         'if (isWindowsHost) "windows-x64" else "linux-x64"',
                         'if (isWindowsHost) "zip" else "tar.gz"',
                         'TargetFormat.Exe, TargetFormat.Msi', 'TargetFormat.AppImage',
                         'fba577c4bb87df04d54dd87bbdaa5a2272f1f99a2acbf9152e1a91b8b5f0b279',
                         '22e09469aaef1190d4320e0621ea35f7e944a872c38b7c485f817672cbe25461ffb82db151e93d413c1889cf42bc486041e54e10c30a2601aa8170ef19e4ed98',
                         '44e25263-67d4-443c-b85c-655a41118add',
                         'it.dir("$desktopRuntimePlatform/letta-code-runtime")']:
            self.assertIn(expected, build)
        release = (REPO / '.github/workflows/release.yml').read_text()
        for asset in ['release-assets/exe/*.exe', 'release-assets/msi/*.msi', 'release-assets/*.yml',
                      'release-assets/*.tar', 'release-assets/*.AppImage', 'release-assets/*.AppImage.sha256']:
            self.assertIn(asset, release)
        self.assertIn('needs: [android-apk, desktop-installers, linux-desktop, server-wrapper]', release)
        self.assertIn('Generate Nucleus Windows update metadata', release)
        self.assertIn('dbus-run-session -- bash ../scripts/desktop/linux-package-smoke.sh', release)

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
                   DBUS_SESSION_BUS_ADDRESS='fixture-only', LETTA_PACKAGE_SMOKE_EVIDENCE=str(evidence))
        result = self.run_smoke_fixture(image, env, terminate_after=options.get('terminate_after'))
        return result, evidence

    def test_every_payload_phase_uses_disposable_home(self):
        result, _ = self.run_window_harness_fixture(options={'write_home': True})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_home_isolated(['extract', 'inspector', 'app'])

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

    def test_standalone_inspector_isolates_node_jni_and_compiler_probes(self):
        layout = self.inspect()
        writer = self.home_writer()
        writer.write_text(writer.read_text().replace('#!/usr/bin/env python3', '#!' + os.sys.executable))
        write(self.node, f'#!/bin/sh\n"{writer}" node "$@"\n'
              'if [ "$1" = --version ]; then echo v24.13.1; fi\n', True)
        write(Path(layout['launcher']), f'#!/bin/sh\n"{writer}" jni "$@"\n', True)
        tools = self.work / 'compiler tools'
        write(tools / 'javac', f'#!/bin/sh\n"{writer}" javac "$@"\n', True)
        layout['natives'] = []  # ELF inspection has its own real format and ldd fixtures.
        env = self.isolated_fixture_env(PATH=str(tools) + os.pathsep + os.environ['PATH'])
        with patch.dict(os.environ, env, clear=True), patch.object(inspector.tempfile, 'tempdir', env['TMPDIR']):
            inspector.run_checks(layout)
            self.assertEqual(os.environ['HOME'], env['HOME'], 'Inspector changed the caller environment')
        self.assert_home_isolated(['node', 'node', 'node', 'node', 'javac', 'jni'])

    def test_windows_smoke_isolates_before_first_bundled_probe(self):
        workflow = (REPO / '.github/workflows/desktop.yml').read_text()
        smoke = workflow.split('      - name: Smoke the packaged Windows app', 1)[1].split('\n      - name:', 1)[0]
        before_probes = smoke.split('$version = & $node --version', 1)[0]
        for key in ['HOME', 'USERPROFILE', 'LETTA_HOME', 'APPDATA', 'LOCALAPPDATA', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME',
                    'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_RUNTIME_DIR', 'JAVA_TOOL_OPTIONS']:
            self.assertIn(f'$env:{key} = ', before_probes, key)

    def test_window_harness_records_success_only_after_visible_window(self):
        result, evidence = self.run_window_harness_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((evidence / 'exit-status.txt').read_text(), '0\n')
        self.assertEqual((evidence / 'window.txt').read_text(), 'Meridian\n')
        self.assertEqual(list(self.work.rglob('letta-linux-package-smoke.*')), [])

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

    def test_failed_native_probe_restores_launcher_config_byte_for_byte(self):
        layout = self.inspect()
        original = self.cfg.read_bytes().replace(b'\n', b'\r\n')
        self.cfg.write_bytes(original)
        probe = self.work / 'owned-probe'
        probe.mkdir()
        failure = subprocess.CalledProcessError(17, ['packaged-launcher'])
        with patch.object(inspector.subprocess, 'run', side_effect=[None, failure]) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                inspector.check_bundled_jvm(layout, probe, env=inspector.isolated_probe_env(probe), javac='javac')
        self.assertEqual(self.cfg.read_bytes(), original)
        self.assertEqual(run.call_args_list[0].args[0],
                         ['javac', '--release', '21', 'PackagingNativeSmoke.java'])
        self.assertEqual(run.call_args_list[0].kwargs['cwd'], probe)

    def test_library_resolution_rejects_missing_libraries_and_host_rpath(self):
        cases = [('libexample.so => not found\n', '', 'Missing library'),
                 ('libexample.so => /usr/lib/libexample.so\n',
                  ' 0x1 (RUNPATH) Library runpath: [/home/runner/build]\n', 'Absolute non-system'),
                 ('libgtk-x11-2.0.so.0 => /usr/lib/libgtk-x11-2.0.so.0\n',
                  ' 0x1 (NEEDED) Shared library: [libgtk-x11-2.0.so.0]\n', 'GTK2 dependency')]
        for ldd, dynamic, message in cases:
            with self.subTest(message=message):
                outputs = [subprocess.CompletedProcess([], 0, ldd), subprocess.CompletedProcess([], 0, dynamic)]
                with patch.object(inspector.subprocess, 'run', side_effect=outputs):
                    with self.assertRaisesRegex(ValueError, message):
                        inspector.inspect_dynamic_library('fixture.so', {})


if __name__ == '__main__':
    unittest.main(verbosity=2)
