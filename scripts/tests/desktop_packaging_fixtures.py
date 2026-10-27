#!/usr/bin/env python3
"""Complete relocated Linux package fixture shared by the packaging test areas."""
import importlib.util
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import zipfile

from desktop_smoke_fixtures import DesktopSmokeFixtures, write

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



class LinuxPackageFixtures(DesktopSmokeFixtures):
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
        self.rive = write(self.root / 'lib/app/resources/librive_desktop_bridge.so', elf())
        self.jar = self.root / 'lib/app/natives.jar'
        self.jar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('mascot/mascot.riv', b'fixture-riv')
            for name in ['native/x86_64-unknown-linux-gnu/libautomerge_jni.so',
                         'linux-x86-64/libiroh_ffi.so', 'libletta_mermaid_renderer.so', 'libletta_tablet_input.so']:
                z.writestr(name, elf())
            # Cross-platform upstream JAR members are allowed and never selected as Linux JNI.
            z.writestr('native/aarch64-unknown-linux-gnu/libautomerge_jni.so', elf(183))
            z.writestr('win32-x86-64/iroh_ffi.dll', b'MZ')
            z.writestr(inspector.hotkey_builder.MEMBER, elf())
            pins = json.loads((inspector.HOTKEY_SOURCE / 'pins.json').read_text())
            z.writestr(inspector.hotkey_builder.PROVENANCE, json.dumps(dict(
                commit=pins['commit'], version=pins['version'], upstream_jar_sha256=pins['jar_sha256'],
                patch_sha256=hashlib.sha256((inspector.HOTKEY_SOURCE / 'preserve-xerror-handler.patch').read_bytes()).hexdigest(),
                source_sha256=pins['patched_source_sha256'], native_sha256=hashlib.sha256(elf()).hexdigest())))
            z.writestr('META-INF/letta/Nucleus-LICENSE.txt', (inspector.HOTKEY_SOURCE / 'LICENSE').read_bytes())


    def inspect(self):
        return inspector.inspect(self.root, self.work / 'native')
