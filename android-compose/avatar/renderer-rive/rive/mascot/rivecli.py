"""The Rive CLI as the tools find and drive it: one binary resolver and one throwaway project.

onion.py, probe.py and pull_editor.py all shell out to `rive`. They resolve it the same way
(`RIVE_BIN`, then ~/.rive/bin/rive.exe, then whatever `rive` is on PATH) and the two that render
a generated variant build it the same way: gen_scene.py writes the document into a temp
directory next to a copy of rive.yaml with its `push:` section stripped, so a stray push can never
happen from there.

Standard library only. Never writes scene.rml.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def rive_bin():
    """The Rive CLI: $RIVE_BIN, else ~/.rive/bin/rive.exe when present, else `rive` on PATH."""
    env = os.environ.get("RIVE_BIN")
    if env:
        return env
    exe = os.path.expanduser("~/.rive/bin/rive.exe")
    return exe if os.path.exists(exe) else "rive"


def animation_duration(document, name):
    """The duration in frames of the LinearAnimation called `name`, or 0 when there is none."""
    m = re.search(r'<LinearAnimation[^>]*\bduration="(\d+)"[^>]*\bname="%s" id=' % re.escape(name), document)
    return int(m.group(1)) if m else 0


def without_push(lines):
    """rive.yaml's lines minus the `push:` block (its header and every indented or blank line under it)."""
    skipping = False
    for line in lines:
        continuation = line[:1].isspace() or not line.strip()
        if skipping and continuation:
            continue
        skipping = line.startswith("push:")
        if not skipping:
            yield line


def temp_project(into, flags, solo=None):
    """Generate a variant document into `into` (gen_scene.py with `flags`, plus `--solo` when asked)
    with an unpushable rive.yaml beside it. Returns (dir, the solo animation's duration or 0)."""
    rml = os.path.join(into, "scene.rml")
    args = list(flags) + (["--solo", solo] if solo else [])
    p = subprocess.run([sys.executable, os.path.join(HERE, "gen_scene.py"), rml] + args,
                       cwd=HERE, capture_output=True, text=True)
    if p.returncode:
        raise SystemExit((p.stderr or p.stdout).strip() or f"gen_scene.py {' '.join(args)} failed")
    duration = 0
    if solo:
        with open(rml, encoding="utf-8") as f:
            duration = animation_duration(f.read(), solo)
    with open(os.path.join(HERE, "rive.yaml"), encoding="utf-8") as f:
        kept = list(without_push(f))
    with open(os.path.join(into, "rive.yaml"), "w", encoding="utf-8", newline="\n") as f:
        f.writelines(kept)
    return into, duration
