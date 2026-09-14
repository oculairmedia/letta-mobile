"""Pull the artist's editor changes back toward the generator.

The generator (gen_scene.py) is the source of truth; the editor is where the art director
retimes and reshapes. This tool shows exactly what the editor changed so those changes can be
ported into gen_scene.py (or, for a one-off, into scene.rml) instead of being lost on the next
regenerate.

    python pull_editor.py <mascot.rev | pulled-project-dir> [--write-report report.md]

Steps it takes:
  1. a .rev is converted with `rive create <tmp> --from-rev=<file.rev>` (the CLI's importer);
     a directory is used as-is (already converted);
  2. both scene.rml files are parsed, push-assigned ids stripped, and every <LinearAnimation>,
     <StateMachine> layer, and the artboard's node tree compared by NAME;
  3. the report lists animations added / removed / changed (with the keyed properties whose
     keyframes differ), machine layers whose transitions differ, and nodes whose attributes
     differ - enough to open gen_scene.py at the right function.

Export the .rev from the editor: File > Export > Download .rev (or the Revisions panel).
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
RIVE = os.path.expanduser("~/.rive/bin/rive.exe")
ID_ATTR = re.compile(r'\s+id="\d+:\d+"')


def convert_rev(rev_path):
    out = tempfile.mkdtemp(prefix="mascot-pull-")
    shutil.rmtree(out)
    subprocess.run([RIVE, "create", out, f"--from-rev={rev_path}"], check=True)
    return out


def load(path):
    text = open(path, encoding="utf-8").read()
    return ET.fromstring(ID_ATTR.sub("", text))


def norm(el):
    """A canonical string for an element subtree, ids already stripped, attribute order fixed."""
    attrs = " ".join(f'{k}="{v}"' for k, v in sorted(el.attrib.items()))
    inner = "".join(norm(c) for c in el)
    return f"<{el.tag} {attrs}>{inner}</{el.tag}>"


def animations(root):
    return {a.get("name"): a for a in root.iter("LinearAnimation")}


def layers(root):
    return {l.get("name"): l for l in root.iter("StateMachineLayer")}


def named_nodes(root):
    out = {}
    for el in root.iter():
        if el.tag in ("LinearAnimation", "StateMachine", "StateMachineLayer", "KeyedObject", "KeyedProperty"):
            continue
        name = el.get("name")
        if name:
            out.setdefault(f"{el.tag}:{name}", el)
    return out


def keyed_diff(ours, theirs):
    """Which (objectId, propertyKey) keyframe lists differ inside two animations."""
    def table(anim):
        t = {}
        for ko in anim.iter("KeyedObject"):
            for kp in ko.iter("KeyedProperty"):
                t[(ko.get("objectId"), kp.get("propertyKey"))] = norm(kp)
        return t
    a, b = table(ours), table(theirs)
    lines = []
    for key in sorted(set(a) | set(b)):
        if a.get(key) != b.get(key):
            state = "added" if key not in a else "removed" if key not in b else "changed"
            lines.append(f"    object {key[0]} property {key[1]}: {state}")
    head = []
    for attr in ("duration", "loopValue", "fps"):
        if ours.get(attr) != theirs.get(attr):
            head.append(f"    {attr}: {ours.get(attr)} -> {theirs.get(attr)}")
    return head + lines


def report(ours_path, theirs_path):
    ours, theirs = load(ours_path), load(theirs_path)
    out = ["# Editor pull report", "", f"ours:   {ours_path}", f"theirs: {theirs_path}", ""]

    oa, ta = animations(ours), animations(theirs)
    out.append("## Animations")
    for name in sorted(set(oa) | set(ta)):
        if name not in oa:
            out.append(f"- ADDED   {name}")
        elif name not in ta:
            out.append(f"- REMOVED {name}")
        elif norm(oa[name]) != norm(ta[name]):
            out.append(f"- CHANGED {name}")
            out.extend(keyed_diff(oa[name], ta[name]))
    out.append("")

    ol, tl = layers(ours), layers(theirs)
    out.append("## State machine layers")
    for name in sorted(set(ol) | set(tl)):
        if name not in ol:
            out.append(f"- ADDED   {name}")
        elif name not in tl:
            out.append(f"- REMOVED {name}")
        elif norm(ol[name]) != norm(tl[name]):
            out.append(f"- CHANGED {name}")
    out.append("")

    on, tn = named_nodes(ours), named_nodes(theirs)
    out.append("## Named nodes (attributes / children)")
    for key in sorted(set(on) | set(tn)):
        if key not in on:
            out.append(f"- ADDED   {key}")
        elif key not in tn:
            out.append(f"- REMOVED {key}")
        elif norm(on[key]) != norm(tn[key]):
            o, t = on[key], tn[key]
            attrs = [f"{k}: {o.get(k)} -> {t.get(k)}" for k in sorted(set(o.attrib) | set(t.attrib)) if o.get(k) != t.get(k)]
            out.append(f"- CHANGED {key}" + (" (" + ", ".join(attrs) + ")" if attrs else " (children)"))
    return "\n".join(out) + "\n"


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    src = argv[1]
    theirs_dir = convert_rev(src) if src.lower().endswith(".rev") else src
    theirs = os.path.join(theirs_dir, "scene.rml")
    text = report(os.path.join(HERE, "scene.rml"), theirs)
    print(text)
    if "--write-report" in argv:
        path = argv[argv.index("--write-report") + 1]
        open(path, "w", encoding="utf-8", newline="\n").write(text)
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
