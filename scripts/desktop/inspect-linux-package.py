#!/usr/bin/env python3
"""Inspect the shipped AppImage tree, including native payloads inside runtime JARs."""
import argparse
import configparser
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def elf_x64(data, name):
    require(len(data) >= 64 and data[:6] == b'\x7fELF\x02\x01', f'Not ELF64 little-endian: {name}')
    require(struct.unpack_from('<H', data, 18)[0] == 62, f'Not x86-64: {name}')


def inspect_desktop_entry(root):
    require((root / 'AppRun').is_file(), 'AppRun missing')
    require(os.access(root / 'AppRun', os.X_OK), 'AppRun is not executable')
    entries = list(root.glob('*.desktop'))
    require(len(entries) == 1, 'Expected one desktop entry')
    desktop = configparser.ConfigParser(interpolation=None)
    desktop.read(entries[0])
    entry = desktop['Desktop Entry']
    require(entry.get('Type') == 'Application' and entry.get('Name') and entry.get('Exec'), 'Invalid desktop entry')
    require(entry.get('Icon') and any(root.glob(entry['Icon'] + '.*')), 'Desktop icon missing')
    require(not entry['Exec'].startswith('/'), 'Desktop entry embeds an absolute path')
    return entries[0], entry


def inspect_node_runtime(root):
    nodes = list(root.rglob('resources/letta-code-runtime/node'))
    require(len(nodes) == 1, 'Expected one bundled Linux Node runtime')
    node = nodes[0]
    runtime = node.parent
    require(os.access(node, os.X_OK), 'Bundled Node is not executable')
    js = runtime / 'node_modules/@letta-ai/letta-code/letta.js'
    require(js.is_file(), 'Bundled AppServer entry missing')
    manifest = json.loads((runtime / 'runtime-manifest.json').read_text())
    package = json.loads(js.with_name('package.json').read_text())
    require(package['version'] == manifest['lettaCode'], 'AppServer does not match runtime manifest')
    return node, js, manifest


def inspect_launcher(root, entry):
    cfgs = [p for p in root.rglob('*.cfg') if 'letta.desktop.runtime.node=' in p.read_text()]
    require(len(cfgs) == 1, 'Packaged launcher runtime binding missing or duplicated')
    cfg = cfgs[0]
    text = cfg.read_text()
    require('app.mainclass=com.letta.mobile.desktop.MainKt' in text, 'Package is not the shared desktop app')
    app_version = re.search(r'(?m)^java-options=-Djpackage.app-version=(.+)$', text)
    require(app_version and entry.get('X-AppImage-Version') == app_version[1],
            'AppImage version does not match the packaged launcher')
    app_ids = re.findall(r'(?m)^java-options=-Dnucleus.app.id=(.+)$', text)
    require(app_ids and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+', app_ids[-1]),
            'Linux app ID must form a valid MPRIS D-Bus name')
    for key, suffix in [('node', 'node'), ('lettaJs', 'node_modules/@letta-ai/letta-code/letta.js')]:
        require(f'-Dletta.desktop.runtime.{key}=$APPDIR/resources/letta-code-runtime/{suffix}' in text,
                f'{key} must use a relocatable $APPDIR binding')
    return cfg


def inspect_host_paths(paths, forbidden):
    for path in paths:
        contents = path.read_text()
        for build_root in forbidden:
            require(build_root not in contents, f'Build path leaked into {path}')
        require(not re.search(r'/(?:home/runner|opt/stacks|Users)/|[A-Z]:\\', contents), f'Host path in {path}')


def inspect_jvm(root, cfg):
    releases = [p for p in root.rglob('runtime/release') if p.is_file()]
    require(len(releases) == 1, 'Bundled Java runtime missing or duplicated')
    release = releases[0]
    # Nucleus jlinks with --strip-native-commands: the shipped app launcher loads libjvm
    # directly, so a standalone bin/java is deliberately absent.
    jvm = release.parent / 'lib/server/libjvm.so'
    require(jvm.is_file(), 'Bundled libjvm missing')
    launcher = cfg.parents[2] / 'bin' / cfg.stem
    require(launcher.is_file() and os.access(launcher, os.X_OK), 'Desktop launcher missing/not executable')
    version = re.search(r'JAVA_VERSION="(\d+)', release.read_text())
    require(version and int(version[1]) >= 25, 'Jewel requires bundled Java 25+')
    # jlink strips OS_ARCH from release; inspect the actual JVM instead of requiring
    # metadata that exists only in the source SDK.
    with jvm.open('rb') as stream:
        elf_x64(stream.read(64), jvm)
    return jvm, launcher


def inspect_payload_path(path, root):
    if path.is_symlink():
        require(path.resolve().is_relative_to(root) and path.exists(), f'External/broken symlink: {path}')
    if path.parent.name == '@img' and path.name.startswith('sharp-'):
        require(path.name in ('sharp-linux-x64', 'sharp-libvips-linux-x64'), f'Foreign sharp: {path}')


def inspect_loose_natives(root):
    natives = []
    for path in root.rglob('*'):
        inspect_payload_path(path, root)
        if not path.is_file():
            continue
        with path.open('rb') as stream:
            header = stream.read(64)
        if header.startswith(b'\x7fELF'):
            elf_x64(header, path)
            natives.append(path)
        elif path.suffix in ('.node', '.so', '.dll', '.exe', '.dylib'):
            raise ValueError(f'Foreign or invalid loose native code: {path}')
    return natives


def extract_jar_natives(jar, native_dir):
    extracted = []
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            # Universal JARs contain other architectures. Select the Linux x64 member.
            if not name.endswith('.so'):
                continue
            if not (re.search(r'(?:x86_64.*linux|linux.*(?:x86[-_]?64|x64|amd64))', name) or Path(name).name in {
                    'libletta_mermaid_renderer.so', 'libletta_tablet_input.so'}):
                continue
            data = archive.read(name)
            elf_x64(data, f'{jar.name}!{name}')
            target = Path(native_dir) / jar.name / name
            require(target.resolve().is_relative_to(Path(native_dir).resolve()), 'Unsafe JAR entry')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            extracted.append(target)
    return extracted


def inspect(root, native_dir, forbidden=()):
    root = Path(root).resolve()
    entry_path, entry = inspect_desktop_entry(root)
    node, js, manifest = inspect_node_runtime(root)
    cfg = inspect_launcher(root, entry)
    inspect_host_paths([cfg, entry_path, root / 'AppRun'], forbidden)
    jvm, launcher = inspect_jvm(root, cfg)
    natives = inspect_loose_natives(root)
    jars = list(root.rglob('*.jar'))
    require(jars, 'Desktop runtime JARs missing')
    required = {'libautomerge_jni.so', 'libiroh_ffi.so', 'libletta_mermaid_renderer.so', 'libletta_tablet_input.so'}
    extracted = [path for jar in jars for path in extract_jar_natives(jar, native_dir)]
    natives.extend(extracted)
    present = {p.name for p in natives}
    require(required <= present, f'Missing Linux JNI payload: {sorted(required - present)}')
    # The current Rive implementation is Windows/D3D11 only; Linux uses the existing fallback.
    require(not list(root.rglob('rive_desktop_bridge.dll')), 'Windows Rive bridge reached Linux image')
    return dict(root=str(root), node=str(node), js=str(js), jvm=str(jvm), launcher=str(launcher), cfg=str(cfg),
                jars=[str(p) for p in jars], natives=[str(p) for p in natives],
                jni=[str(p) for p in extracted if p.name in required], manifest=manifest)


def inspect_dynamic_library(path, library_env):
    result = subprocess.run(['ldd', path], env=library_env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    dynamic = subprocess.run(['readelf', '-d', path], check=True, text=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout
    for value in re.findall(r'\((?:RPATH|RUNPATH)\)[^\[\(\n]*\[([^\]\n]*)\]', dynamic):
        for search_path in value.split(':'):
            require(not search_path.startswith('/') or search_path in ('/lib', '/lib64', '/usr/lib', '/usr/lib64'),
                    f'Absolute non-system library search path in {path}: {search_path}')
    # Static executables are valid, but missing DT_NEEDED libraries are not.
    require('not found' not in result.stdout, f'Missing library for {path}:\n{result.stdout}')
    require(result.returncode == 0 or 'not a dynamic executable' in result.stdout or
            'statically linked' in result.stdout, f'ldd failed for {path}:\n{result.stdout}')


def check_bundled_node(layout):
    version = subprocess.check_output([layout['node'], '--version'], text=True).strip()
    require(version == 'v' + layout['manifest']['node'], 'Wrong bundled Node version')
    subprocess.run([layout['node'], '-e',
                    'const r = require("node:module").createRequire(process.argv[1]); '
                    'for (const name of ["sharp", "@janhapke/sharp-electron", "node-pty"]) { '
                    'r(name); console.log("Loaded", name); }', layout['js']], check=True, timeout=60)
    subprocess.run([layout['node'], layout['js'], '--version'], check=True, timeout=60)
    subprocess.run([layout['node'], layout['js'], 'server', '--help'], check=True, timeout=60)


def run_checks(layout):
    jvm = Path(layout['jvm'])
    # AppRun prepends its usr/lib; resolve bundled dependencies the same way.
    library_env = dict(os.environ, LD_LIBRARY_PATH=os.pathsep.join([
        str(Path(layout['root']) / 'usr/lib'), str(jvm.parent), str(jvm.parent.parent),
    ]))
    for path in layout['natives']:
        inspect_dynamic_library(path, library_env)
    check_bundled_node(layout)
    # Compile into a fresh owned directory, never a caller-selected path/option.
    with tempfile.TemporaryDirectory(prefix='letta-linux-jni-') as probe:
        check_bundled_jvm(layout, Path(probe))
    print(f"Verified {len(layout['natives'])} Linux x64 ELF files and four required JNI libraries")


def check_bundled_jvm(layout, work):
    # Load the shipped JNI into the shipped JVM, not the build JDK. Compilation is CI-only.
    source = Path(work) / 'PackagingNativeSmoke.java'
    source.write_text('public class PackagingNativeSmoke { public static void main(String[] paths) { '
                      'if (Runtime.version().feature() < 25) throw new Error("Jewel requires Java 25+"); '
                      'for (String key : new String[]{"node", "lettaJs"}) { '
                      'java.io.File f = new java.io.File(System.getProperty("letta.desktop.runtime." + key)); '
                      'if (!f.isAbsolute() || !f.isFile()) throw new Error("Unresolved bundled path: " + f); } '
                      'for (String path : paths) { System.load(path); System.out.println("Loaded " + path); } } }')
    subprocess.run(['javac', '--release', '21', 'PackagingNativeSmoke.java'], cwd=work, check=True)
    # Exercise jpackage's shipped launcher/JVM and its $APPDIR expansion. Only the extracted
    # test copy is temporarily redirected to the tiny native-load entry point, then restored.
    cfg = Path(layout['cfg'])
    backup = work / 'original.cfg'
    shutil.copyfile(cfg, backup)
    patched = cfg.read_text().replace('app.mainclass=com.letta.mobile.desktop.MainKt', 'app.mainclass=PackagingNativeSmoke')
    patched, count = re.subn(r'(?m)^app.classpath=(.*)$',
                            lambda match: 'app.classpath=' + str(work) + os.pathsep + match[1], patched, count=1)
    require(count == 1, 'Launcher classpath missing')
    try:
        cfg.write_text(patched)
        subprocess.run([layout['launcher'], *layout['jni']], check=True, timeout=60)
    finally:
        # Copy the original bytes back, including line endings, even if the launcher fails.
        shutil.copyfile(backup, cfg)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('work', type=Path)
    parser.add_argument('--forbid-path', action='append', default=[])
    parser.add_argument('--run', action='store_true')
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    result = inspect(args.root, args.work / 'native', args.forbid_path)
    (args.work / 'layout.json').write_text(json.dumps(result, indent=2))
    if args.run:
        run_checks(result)
