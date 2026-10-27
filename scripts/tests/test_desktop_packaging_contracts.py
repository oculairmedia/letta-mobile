"""Desktop packaging regression tests: contracts."""
import unittest

from desktop_packaging_fixtures import LinuxPackageFixtures, REPO


class DesktopPackagingContractTests(LinuxPackageFixtures, unittest.TestCase):
    def test_release_declares_native_hotkey_build_prerequisites(self):
        release = (REPO / '.github/workflows/release.yml').read_text()
        setup = release.split('name: Install Linux packaging and X11 smoke dependencies', 1)[1].split('\n      - name:', 1)[0]
        for dependency in ['gcc', 'pkg-config', 'patch', 'libx11-dev', 'libglib2.0-dev']:
            self.assertIn(dependency, setup)


    def test_hotkey_arguments_are_lazy_and_task_filters_do_not_realize_tasks(self):
        build = (REPO / 'android-compose/desktop/build.gradle.kts').read_text()
        hotkey = build.split('tasks.register<Exec>("buildDesktopLinuxHotkey")', 1)[1].split('} else {', 1)[0]
        self.assertIn('objects.newInstance<DesktopHotkeyArguments>()', hotkey)
        self.assertIn('argumentProviders.add(hotkeyArguments)', hotkey)
        self.assertNotIn('CommandLineArgumentProvider {\n            listOf(', hotkey)
        self.assertNotIn('doFirst', hotkey)
        self.assertNotIn('tasks.matching', build)
        self.assertIn('inputs.files(upstreamHotkey', hotkey)
        self.assertIn('builtBy(checkNotNull(buildDesktopLinuxHotkey))', build)


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


    def test_windows_smoke_retains_native_window_diagnostics(self):
        workflow = (REPO / '.github/workflows/desktop.yml').read_text()
        smoke = workflow.split('      - name: Smoke the packaged Windows app', 1)[1].split('\n      - name:', 1)[0]
        script = (REPO / 'scripts/desktop/windows-package-smoke.ps1').read_text()
        self.assertIn('windows-package-smoke.ps1', smoke)
        self.assertIn('GetWindowThreadProcessId', script)
        self.assertIn('IsWindowVisible', script)
        self.assertIn('GetWindow(hwnd, 4)', script)
        self.assertIn("Write-WindowSnapshot 'before-cleanup'", script)
        self.assertIn("'desktop.png'", script)
        self.assertIn('Get-EligibleFrame $App.Id $ImagePath $LauncherPath', script)
        self.assertIn('Confirm-StableFrame $frame', script)
        self.assertIn("$Window.Class -ne 'SunAwtFrame'", script)
        self.assertIn('Test-PackagedChild $process $imagePrefix', script)
        self.assertIn('Stopwatch]::StartNew()', script)
        self.assertNotIn('if ($app.MainWindowHandle -ne 0)', script)
        self.assertNotIn('MainWindowTitle -match', script)


    def test_windows_smoke_isolates_before_first_bundled_probe(self):
        smoke = (REPO / 'scripts/desktop/windows-package-smoke.ps1').read_text()
        before_probes = smoke.split('$version = & $node --version', 1)[0]
        for key in ['HOME', 'USERPROFILE', 'LETTA_HOME', 'APPDATA', 'LOCALAPPDATA', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME',
                    'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_RUNTIME_DIR', 'JAVA_TOOL_OPTIONS']:
            self.assertIn(f'$env:{key} = ', before_probes, key)


if __name__ == '__main__':
    unittest.main(verbosity=2)
