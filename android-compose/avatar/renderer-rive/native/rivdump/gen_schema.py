"""Builds schema.tsv for rivdump from rive-runtime's generated headers.

One row per authored property: typeKey \t propertyKey \t fieldKind \t TypeName \t propName.
rivdump reads a property off an object only when the object isTypeOf(typeKey), so the dump
carries exactly what the runtime can deserialize - no guessing at keys.

usage: python gen_schema.py <rive-runtime dir> > schema.tsv
"""
import glob
import os
import re
import sys

if len(sys.argv) < 2:
    raise SystemExit("usage: python gen_schema.py <rive-runtime dir>")
root = sys.argv[1]
headers = glob.glob(os.path.join(root, "include", "rive", "generated", "**", "*_base.hpp"), recursive=True)
if not headers:
    raise SystemExit(f"no generated Rive headers found under {root!r}")
type_re = re.compile(r"class (\w+)Base\b.*?static const uint16_t typeKey = (\d+);", re.S)
key_re = re.compile(r"static const uint16_t (\w+)PropertyKey = (\d+);")
# Tolerates the editor-only #ifdef around some names and CoreIdType's runtimeDeserialize.
deser_re = re.compile(
    r"case (\w+)PropertyKey:\s*\n(?:\s*#ifdef[^\n]*\n)?\s*m_\w+ =\s*Core(\w+)Type::(?:runtimeD|d)eserialize", re.S)

rows = []
for path in headers:
    src = open(path, encoding="utf-8", errors="replace").read()
    m = type_re.search(src)
    if not m:
        continue
    name, type_key = m.group(1), int(m.group(2))
    # A name row (key -1) per type, so types with no own properties still get a name in the dump.
    rows.append((type_key, -1, "Type", name, "-"))
    kinds = {p: k for p, k in deser_re.findall(src)}
    for prop, key in key_re.findall(src):
        kind = kinds.get(prop)
        if kind == "Id":
            kind = "Uint"  # ids read through getUint
        if kind is None:
            # Callbacks and derived properties have no deserialize case; skip them.
            continue
        rows.append((type_key, int(key), kind, name, prop))

if not rows:
    raise SystemExit("generated headers produced no supported view-model or core types")
rows.sort()
for r in rows:
    print("\t".join(map(str, r)))
print(f"{len(rows)} properties across {len(set(r[0] for r in rows))} types", file=sys.stderr)
