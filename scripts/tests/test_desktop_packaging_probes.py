"""Desktop packaging regression tests: probes."""
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

from desktop_packaging_fixtures import LinuxPackageFixtures, inspector, write


class LinuxPackageProbeTests(LinuxPackageFixtures, unittest.TestCase):
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
        with patch.dict(os.environ, env, clear=True), patch.object(inspector.tempfile, 'tempdir', env['TMPDIR']), \
                patch.object(inspector, 'check_bundled_rive') as rive:
            inspector.run_checks(layout)
            rive.assert_called_once()
            self.assertTrue(Path(rive.call_args.args[1]).is_relative_to(Path(env['TMPDIR'])))
            self.assertNotEqual(rive.call_args.args[2]['HOME'], env['HOME'])
            self.assertEqual(os.environ['HOME'], env['HOME'], 'Inspector changed the caller environment')
        self.assert_home_isolated(['node', 'node', 'node', 'node', 'javac', 'jni'])


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
                         ['javac', '--release', '21', '-cp', str(self.jar), 'PackagingNativeSmoke.java'])
        self.assertEqual(run.call_args_list[0].kwargs['cwd'], probe)


    def test_packaged_rive_probe_uses_shipped_asset_and_propagates_gpu_failure(self):
        layout = self.inspect()
        work = self.work / 'rive-probe'
        work.mkdir()
        env = inspector.isolated_probe_env(work)
        failure = subprocess.CalledProcessError(1, ['smoke-linux.py'])
        with patch.object(inspector.subprocess, 'run', side_effect=failure) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                inspector.check_bundled_rive(layout, work, env)
        args = run.call_args.args[0]
        self.assertEqual(args[2], str(self.rive))
        self.assertEqual(Path(args[3]).read_bytes(), b'fixture-riv')
        self.assertEqual(run.call_args.kwargs['env'], env)
        self.assertTrue(run.call_args.kwargs['check'])


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
