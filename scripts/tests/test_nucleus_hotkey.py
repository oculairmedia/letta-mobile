#!/usr/bin/env python3
"""Lightweight JAR/pin regressions. Real C/X11 regressions run in Linux CI."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'android-compose/native/nucleus_hotkey'
spec = importlib.util.spec_from_file_location('hotkey_builder', SOURCE / 'build.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class HotkeyPackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        self.upstream = self.work / 'upstream.jar'
        self.output = self.work / 'derived.jar'
        self.pins = json.loads((SOURCE / 'pins.json').read_text())
        self.native = b'patched Linux native fixture'
        self.metadata = dict(version=self.pins['version'], commit=self.pins['commit'],
                             upstream_jar_sha256=self.pins['jar_sha256'],
                             patch_sha256=builder.sha256((SOURCE / 'preserve-xerror-handler.patch').read_bytes()),
                             source_sha256=self.pins['patched_source_sha256'],
                             native_sha256=builder.sha256(self.native))
        with zipfile.ZipFile(self.upstream, 'w') as archive:
            archive.writestr(builder.MEMBER, b'original Linux native fixture')
            archive.writestr('nucleus/native/win32-x64/nucleus_global_hotkey.dll', b'Windows native fixture')
            archive.writestr('dev/nucleusframework/Bridge.class', b'Java bytecode fixture')

    def derive(self):
        builder.write_jar(self.upstream, self.output, self.native, self.metadata)

    def test_only_linux_member_changes_and_license_is_shipped(self):
        self.derive()
        with zipfile.ZipFile(self.upstream) as original, zipfile.ZipFile(self.output) as derived:
            for name in original.namelist():
                self.assertEqual(derived.read(name), self.native if name == builder.MEMBER else original.read(name))
        self.assertEqual(builder.verify_jar(self.output, self.pins), self.metadata)

    def test_incorrect_native_digest_is_rejected(self):
        self.metadata['native_sha256'] = '0' * 64
        self.derive()
        with self.assertRaisesRegex(ValueError, 'native checksum'):
            builder.verify_jar(self.output, self.pins)

    def test_wrong_source_pin_is_rejected(self):
        for field in ('commit', 'upstream_jar_sha256', 'patch_sha256', 'source_sha256'):
            with self.subTest(field=field):
                metadata = dict(self.metadata, **{field: 'wrong'})
                builder.write_jar(self.upstream, self.output, self.native, metadata)
                with self.assertRaises(ValueError):
                    builder.verify_jar(self.output, self.pins)

    def test_wrong_upstream_jar_fails_before_download_or_compilation(self):
        with patch.object(builder, 'fetch_sources') as fetch, patch.object(builder.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'Upstream hotkey JAR checksum'):
                builder.build(self.upstream, self.output, '/unused-jdk')
            fetch.assert_not_called()
            run.assert_not_called()

    def test_source_checksum_mismatch_fails_before_write(self):
        pins = dict(self.pins, files={'LICENSE': '0' * 64})
        with patch.object(builder.urllib.request, 'urlopen') as response:
            response.return_value.__enter__.return_value.read.return_value = b'corrupted source'
            with self.assertRaisesRegex(ValueError, 'Source checksum mismatch'):
                builder.fetch_sources(self.work, pins)
        self.assertFalse((self.work / 'LICENSE').exists())

    def test_signed_jar_is_rejected(self):
        with zipfile.ZipFile(self.upstream, 'a') as archive:
            archive.writestr('META-INF/upstream.RSA', b'signature')
        with self.assertRaisesRegex(ValueError, 'Signed JAR'):
            self.derive()


if __name__ == '__main__':
    unittest.main()
