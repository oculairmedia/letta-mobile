"""Desktop packaging regression tests: payload."""
import unittest
import zipfile

from desktop_packaging_fixtures import LinuxPackageFixtures, elf, inspector, write


class LinuxPackagePayloadTests(LinuxPackageFixtures, unittest.TestCase):
    def test_complete_relocated_package_and_universal_jars(self):
        layout = self.inspect()
        self.assertEqual(len(layout['jni']), 5)
        self.assertEqual(layout['node'], str(self.node))


    def test_missing_linux_rive_bridge_is_rejected(self):
        self.rive.unlink()
        with self.assertRaisesRegex(ValueError, 'packaged Linux Rive GPU bridge'):
            self.inspect()


    def test_wrong_rive_architecture_is_rejected(self):
        self.rive.write_bytes(elf(183))
        with self.assertRaisesRegex(ValueError, 'Not x86-64'):
            self.inspect()


    def test_rive_bridge_uses_the_existing_packaged_resources_directory(self):
        self.assertEqual(self.inspect()['rive'], str(self.rive))


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


    def test_unpatched_hotkey_payload_is_rejected(self):
        with zipfile.ZipFile(self.jar) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()
                       if name != inspector.hotkey_builder.PROVENANCE}
        with zipfile.ZipFile(self.jar, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        with self.assertRaises(KeyError):
            self.inspect()


    def test_second_hotkey_jar_is_rejected(self):
        with zipfile.ZipFile(self.jar.with_name('duplicate.jar'), 'w') as archive:
            archive.writestr(inspector.hotkey_builder.MEMBER, elf())
        with self.assertRaisesRegex(ValueError, 'Expected one patched Linux hotkey JNI'):
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


    def test_selected_linux_jni_with_wrong_architecture_is_rejected(self):
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('linux-x86-64/libiroh_ffi.so', elf(183))
        with self.assertRaisesRegex(ValueError, 'Not x86-64'):
            self.inspect()


if __name__ == '__main__':
    unittest.main(verbosity=2)
