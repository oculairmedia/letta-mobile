#!/usr/bin/env python3
"""Build the pinned Linux hotkey JNI; never edit Maven caches or host libraries."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
MEMBER = 'nucleus/native/linux-x64/libnucleus_global_hotkey.so'
PROVENANCE = 'META-INF/letta/nucleus-hotkey.json'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fetch_sources(work, pins):
    base = f'https://raw.githubusercontent.com/NucleusFramework/Nucleus/{pins["commit"]}/'
    for name, expected in pins['files'].items():
        with urllib.request.urlopen(base + name, timeout=60) as response:
            data = response.read()
        require(sha256(data) == expected, f'Source checksum mismatch: {name}')
        target = work / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    require((work / 'LICENSE').read_bytes() == (HERE / 'LICENSE').read_bytes(), 'License mismatch')


def compiler_args(jdk, source):
    # Same headers, flags and links as the checksum-verified upstream build.sh.
    # Do not execute that script: its final step deletes a user's native cache.
    headers = Path(jdk) / 'include'
    require((headers / 'jni.h').is_file(), 'JNI headers missing')
    flags = subprocess.check_output(['pkg-config', '--cflags', 'gio-2.0'], text=True)
    libs = subprocess.check_output(['pkg-config', '--libs', 'gio-2.0'], text=True)
    return (['gcc', '-fPIC', '-I' + str(headers), '-I' + str(headers / 'linux'), '-I' + str(source)] +
            shlex.split(flags) + ['-Os', '-flto', '-fvisibility=hidden',
                                 '-Wl,--gc-sections', '-Wl,--strip-all']), ['-lX11', '-lpthread'] + shlex.split(libs)


def regression(work, compiler, links, patched):
    executable = work / ('patched-test' if patched else 'upstream-test')
    subprocess.run(compiler + ['-o', str(executable), str(HERE / 'handler_test.c')] + links, check=True)
    result = subprocess.run(['xvfb-run', '-a', str(executable)], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    print(result.stdout, end='', flush=True)
    if patched:
        require(result.returncode == 0, 'Patched handler regression failed')
    else:
        require(result.returncode == 42 and 'LOST_HANDLER: successful grab' in result.stdout,
                'Unpatched control did not reproduce handler loss')


def write_jar(upstream, output, native, metadata):
    with zipfile.ZipFile(upstream) as original, zipfile.ZipFile(output, 'w') as derived:
        names = original.namelist()
        require(names.count(MEMBER) == 1, 'Expected exactly one upstream Linux x64 hotkey member')
        require(len(names) == len(set(names)), 'Duplicate upstream JAR entries')
        require(not any(n.upper().endswith(('.SF', '.RSA', '.DSA')) for n in names), 'Signed JAR needs explicit handling')
        for entry in original.infolist():
            derived.writestr(entry, native if entry.filename == MEMBER else original.read(entry))
        derived.writestr(PROVENANCE, json.dumps(metadata, sort_keys=True) + '\n')
        derived.writestr('META-INF/letta/Nucleus-LICENSE.txt', (HERE / 'LICENSE').read_bytes())
    # All bytecode and other platforms' members must remain byte-identical.
    with zipfile.ZipFile(upstream) as original, zipfile.ZipFile(output) as derived:
        for name in original.namelist():
            if name != MEMBER:
                require(original.read(name) == derived.read(name), f'Unexpected changed JAR member: {name}')


def verify_jar(jar, pins):
    with zipfile.ZipFile(jar) as archive:
        require(archive.namelist().count(MEMBER) == 1, 'Duplicate hotkey native member')
        metadata = json.loads(archive.read(PROVENANCE))
        require(metadata['commit'] == pins['commit'] and metadata['version'] == pins['version'], 'Hotkey pin mismatch')
        require(metadata['upstream_jar_sha256'] == pins['jar_sha256'], 'Hotkey upstream JAR mismatch')
        require(metadata['patch_sha256'] == sha256((HERE / 'preserve-xerror-handler.patch').read_bytes()),
                'Hotkey patch mismatch')
        require(metadata['source_sha256'] == pins['patched_source_sha256'], 'Patched hotkey source mismatch')
        require(metadata['native_sha256'] == sha256(archive.read(MEMBER)), 'Hotkey native checksum mismatch')
        require(archive.read('META-INF/letta/Nucleus-LICENSE.txt') == (HERE / 'LICENSE').read_bytes(),
                'Packaged hotkey license mismatch')
        return metadata


def build(upstream, output, jdk):
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'Linux x86-64 build required')
    pins = json.loads((HERE / 'pins.json').read_text())
    require(sha256(upstream.read_bytes()) == pins['jar_sha256'], 'Upstream hotkey JAR checksum mismatch')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='hotkey-', dir=output.parent) as temporary:
        work = Path(temporary)
        fetch_sources(work, pins)
        source = work / 'global-hotkey/src/main/native/linux'
        compiler, links = compiler_args(jdk, source)
        regression(work, compiler, links, patched=False)
        subprocess.run(['patch', '--batch', '--fuzz=0', '-p1', '-i', str(HERE / 'preserve-xerror-handler.patch')],
                       cwd=work, check=True)
        require(sha256((source / 'nucleus_global_hotkey_linux.c').read_bytes()) == pins['patched_source_sha256'],
                'Patched source checksum mismatch')
        regression(work, compiler, links, patched=True)
        native = work / 'libnucleus_global_hotkey.so'
        subprocess.run(compiler + ['-shared', '-o', str(native), str(source / 'nucleus_global_hotkey_linux.c')] + links,
                       check=True)
        metadata = dict(version=pins['version'], commit=pins['commit'], upstream_jar_sha256=pins['jar_sha256'],
                        patch_sha256=sha256((HERE / 'preserve-xerror-handler.patch').read_bytes()),
                        source_sha256=sha256((source / 'nucleus_global_hotkey_linux.c').read_bytes()),
                        native_sha256=sha256(native.read_bytes()))
        derived = work / output.name
        write_jar(upstream, derived, native.read_bytes(), metadata)
        verify_jar(derived, pins)
        os.replace(derived, output)
        print('Built and verified Linux hotkey:', json.dumps(metadata, sort_keys=True), flush=True)


if __name__ == '__main__':
    build(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(), sys.argv[3])
