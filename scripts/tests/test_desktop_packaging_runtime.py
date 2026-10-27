"""Desktop packaging regression tests: runtime."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import unittest

from desktop_packaging_fixtures import LinuxPackageFixtures, SCRIPT, elf, write


class LinuxRuntimeStagingTests(LinuxPackageFixtures, unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main(verbosity=2)
