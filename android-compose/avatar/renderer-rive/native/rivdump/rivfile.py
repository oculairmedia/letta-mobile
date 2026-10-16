"""A pure-Python reader for the object stream of a `.riv` - enough to check a contract, no CLI.

The Rive CLI is a Windows binary that CI does not have, and `rive inspect` needs the source
project, not the shipped file. This reads the SHIPPED file itself:

    header      "RIVE", major / minor version, file id (varuints), then the table of contents:
                property keys (varuints, 0-terminated) and their field types, 2 bits each,
                packed into little-endian uint32s
    objects     typeKey (varuint), then (propertyKey, value) pairs until a propertyKey of 0

A value's encoding comes from the table of contents when the key is listed there, otherwise from
`schema.tsv` (generated from rive-runtime's headers by gen_schema.py, which records every
property's field kind): uint / int / bool are varuints, string is a varuint length and UTF-8
bytes, double is a little-endian float32, colour a little-endian uint32. The same table names
the types and properties, so the result reads like `rive inspect` output.

    from rivfile import read
    riv = read("mascot.riv")
    riv.objects     # [RivObject(type="ViewModelPropertyNumber", props={"name": "lookX"}), ...]

Standard library only.
"""
import os
import struct
from typing import NamedTuple

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = os.path.join(HERE, "schema.tsv")

_TOC_KIND = {0: "Uint", 1: "String", 2: "Double", 3: "Color"}


class RivObject(NamedTuple):
    type: str          # the runtime's type name ("ViewModelPropertyNumber"), or "#<key>" if unknown
    props: dict        # property name -> value


class Riv(NamedTuple):
    major: int
    minor: int
    objects: list


class Schema(NamedTuple):
    types: dict        # typeKey -> type name
    kinds: dict        # propertyKey -> field kind (Uint, Int, Bool, String, Double, Color)
    names: dict        # propertyKey -> property name


def load_schema(path=SCHEMA):
    types, kinds, names = {}, {}, {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            type_key, prop_key, kind, type_name, prop = line.rstrip("\n").split("\t")
            if kind == "Type":
                types[int(type_key)] = type_name
            else:
                kinds[int(prop_key)] = kind
                names[int(prop_key)] = prop
    return Schema(types, kinds, names)


class _Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def varuint(self):
        result = shift = 0
        while True:
            if self.pos >= len(self.data):
                raise ValueError("truncated varuint")
            b = self.data[self.pos]
            self.pos += 1
            result |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                return result

    def fixed(self, fmt, size):
        if self.pos + size > len(self.data):
            raise ValueError("truncated value")
        value = struct.unpack_from(fmt, self.data, self.pos)[0]
        self.pos += size
        return value

    def string(self):
        n = self.varuint()
        raw = self.data[self.pos:self.pos + n]
        if len(raw) != n:
            raise ValueError("truncated string")
        self.pos += n
        return raw.decode("utf-8", "replace")

    def value(self, kind):
        if kind in ("Uint", "Int", "Bool"):
            return self.varuint()
        if kind == "String":
            return self.string()
        if kind == "Double":
            return self.fixed("<f", 4)
        if kind == "Color":
            return self.fixed("<I", 4)
        raise ValueError(f"unknown field kind {kind}")


def _toc(r):
    keys = []
    while True:
        k = r.varuint()
        if k == 0:
            break
        keys.append(k)
    kinds, word, bit = {}, 0, 8
    for k in keys:
        if bit == 8:
            word, bit = r.fixed("<I", 4), 0
        kinds[k] = _TOC_KIND[(word >> bit) & 3]
        bit += 2
    return kinds


def parse(data, schema=None):
    """Riv(major, minor, objects) from the bytes of a `.riv`."""
    schema = schema or load_schema()
    r = _Reader(data)
    if data[:4] != b"RIVE":
        raise ValueError("not a .riv (no RIVE fingerprint)")
    r.pos = 4
    major, minor, _file_id = r.varuint(), r.varuint(), r.varuint()
    toc = _toc(r)
    objects = []
    while r.pos < len(data):
        type_key = r.varuint()
        props = {}
        while True:
            key = r.varuint()
            if key == 0:
                break
            kind = toc.get(key) or schema.kinds.get(key)
            if kind is None:
                raise ValueError(f"property {key} on type {type_key} is in neither the file's table "
                                 f"of contents nor schema.tsv; regenerate schema.tsv (gen_schema.py)")
            props[schema.names.get(key, f"#{key}")] = r.value(kind)
        objects.append(RivObject(schema.types.get(type_key, f"#{type_key}"), props))
    return Riv(major, minor, objects)


def read(path, schema=None):
    with open(path, "rb") as f:
        return parse(f.read(), schema)
