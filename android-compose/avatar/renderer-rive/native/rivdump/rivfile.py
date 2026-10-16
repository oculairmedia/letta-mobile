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
    """A cursor over the bytes; each read advances it and refuses to run past the end."""

    def __init__(self, data):
        self.data, self.pos = data, 0

    def _take(self, n):
        if self.pos + n > len(self.data):
            raise ValueError("truncated .riv")
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def byte(self):
        return self._take(1)[0]

    def varuint(self):
        result = shift = 0
        b = 0x80
        while b & 0x80:
            b = self.byte()
            result |= (b & 0x7F) << shift
            shift += 7
        return result

    def fixed(self, fmt, size):
        return struct.unpack(fmt, self._take(size))[0]

    def string(self):
        return self._take(self.varuint()).decode("utf-8", "replace")

    def at_end(self):
        return self.pos >= len(self.data)


# Field kind -> how its value is encoded.
_VALUE_READERS = {
    "Uint": _Reader.varuint, "Int": _Reader.varuint, "Bool": _Reader.varuint,
    "String": _Reader.string,
    "Double": lambda r: r.fixed("<f", 4),
    "Color": lambda r: r.fixed("<I", 4),
}


def _keys_until_zero(r):
    """Varuints up to (not including) a terminating 0."""
    keys = []
    key = r.varuint()
    while key:
        keys.append(key)
        key = r.varuint()
    return keys


def _toc(r):
    """The table of contents: property key -> field kind, 2 bits each in little-endian uint32s."""
    keys = _keys_until_zero(r)
    words = [r.fixed("<I", 4) for _ in range((len(keys) + 3) // 4)]
    return {k: _TOC_KIND[(words[i // 4] >> (2 * (i % 4))) & 3] for i, k in enumerate(keys)}


def _header(r):
    """(major, minor, table of contents); the file id is read and dropped."""
    if r._take(4) != b"RIVE":
        raise ValueError("not a .riv (no RIVE fingerprint)")
    major, minor, _file_id = r.varuint(), r.varuint(), r.varuint()
    return major, minor, _toc(r)


def _kind(key, type_key, toc, schema):
    kind = toc.get(key) or schema.kinds.get(key)
    if kind is None:
        raise ValueError(f"property {key} on type {type_key} is in neither the file's table "
                         f"of contents nor schema.tsv; regenerate schema.tsv (gen_schema.py)")
    return kind


def _object(r, toc, schema):
    """One object record: its type key, then (key, value) pairs up to a 0 key."""
    type_key = r.varuint()
    props = {}
    key = r.varuint()
    while key:
        props[schema.names.get(key, f"#{key}")] = _VALUE_READERS[_kind(key, type_key, toc, schema)](r)
        key = r.varuint()
    return RivObject(schema.types.get(type_key, f"#{type_key}"), props)


def parse(data, schema=None):
    """Riv(major, minor, objects) from the bytes of a `.riv`."""
    schema = schema or load_schema()
    r = _Reader(data)
    major, minor, toc = _header(r)
    objects = []
    while not r.at_end():
        objects.append(_object(r, toc, schema))
    return Riv(major, minor, objects)


def read(path, schema=None):
    with open(path, "rb") as f:
        return parse(f.read(), schema)
