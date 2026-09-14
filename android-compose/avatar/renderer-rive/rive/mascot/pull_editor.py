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

from rivecli import rive_bin

HERE = os.path.dirname(os.path.abspath(__file__))
ID_ATTR = re.compile(r'\s+id="\d+:\d+"')
UNNAMED_CONTAINERS = ("LinearAnimation", "StateMachine", "StateMachineLayer", "KeyedObject", "KeyedProperty")


def convert_rev(rev_path):
    out = tempfile.mkdtemp(prefix="mascot-pull-")
    shutil.rmtree(out)
    subprocess.run([rive_bin(), "create", out, f"--from-rev={rev_path}"], check=True)
    return out


def load(path):
    with open(path, encoding="utf-8") as f:
        return ET.fromstring(ID_ATTR.sub("", f.read()))


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
        name = el.get("name")
        if name and el.tag not in UNNAMED_CONTAINERS:
            out.setdefault(f"{el.tag}:{name}", el)
    return out


def keyed_table(anim):
    """{(objectId, propertyKey): canonical keyframes} for one animation."""
    return {(ko.get("objectId"), kp.get("propertyKey")): norm(kp)
            for ko in anim.iter("KeyedObject") for kp in ko.iter("KeyedProperty")}


def presence(key, ours, theirs):
    """'added' | 'removed' | 'changed' for a key present on at least one side."""
    if key not in ours:
        return "added"
    return "removed" if key not in theirs else "changed"


def keyed_diff(ours, theirs):
    """Which (objectId, propertyKey) keyframe lists differ inside two animations."""
    a, b = keyed_table(ours), keyed_table(theirs)
    head = [f"    {attr}: {ours.get(attr)} -> {theirs.get(attr)}"
            for attr in ("duration", "loopValue", "fps") if ours.get(attr) != theirs.get(attr)]
    lines = [f"    object {key[0]} property {key[1]}: {presence(key, a, b)}"
             for key in sorted(set(a) | set(b)) if a.get(key) != b.get(key)]
    return head + lines


def attribute_changes(ours, theirs):
    """' (k: a -> b, ...)' for the attributes that differ, or ' (children)' when only children do."""
    attrs = [f"{k}: {ours.get(k)} -> {theirs.get(k)}"
             for k in sorted(set(ours.attrib) | set(theirs.attrib)) if ours.get(k) != theirs.get(k)]
    return " (" + ", ".join(attrs) + ")" if attrs else " (children)"


def section(title, ours, theirs, detail):
    """One report section: every name on either side that was added, removed or changed.
    `detail(ours_el, theirs_el)` returns (suffix on the CHANGED line, extra lines under it)."""
    out = [title]
    for name in sorted(set(ours) | set(theirs)):
        if name not in ours:
            out.append(f"- ADDED   {name}")
        elif name not in theirs:
            out.append(f"- REMOVED {name}")
        elif norm(ours[name]) != norm(theirs[name]):
            suffix, extra = detail(ours[name], theirs[name])
            out.append(f"- CHANGED {name}{suffix}")
            out.extend(extra)
    return out


def report(ours_path, theirs_path):
    ours, theirs = load(ours_path), load(theirs_path)
    out = ["# Editor pull report", "", f"ours:   {ours_path}", f"theirs: {theirs_path}", ""]
    out += section("## Animations", animations(ours), animations(theirs),
                   lambda o, t: ("", keyed_diff(o, t)))
    out.append("")
    out += section("## State machine layers", layers(ours), layers(theirs), lambda o, t: ("", []))
    out.append("")
    out += section("## Named nodes (attributes / children)", named_nodes(ours), named_nodes(theirs),
                   lambda o, t: (attribute_changes(o, t), []))
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
