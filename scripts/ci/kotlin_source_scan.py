"""Small Kotlin source reader shared by the parity guardrails (letta-mobile-ewtaq).

It is deliberately not a parser: it knows enough Kotlin lexing (comments, strings with
templates, char literals, nested brackets) to find primary constructors, constructor calls and
top-level declarations in production sources without compiling them.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

ANDROID_COMPOSE = "android-compose"
TEST_SOURCE_SETS = {"test", "androidTest", "wasmJsTest", "jvmTest", "commonTest", "androidUnitTest", "testFixtures"}

# Platform modules: they bind the shared code and add nothing else. Paths are relative to the repo.
PLATFORM_MODULES = {
    "desktop": ["desktop"],
    "web": ["web"],
    "android": ["app", "feature-*"],
}
# Modules whose production code is the shared implementation platforms must reuse.
SHARED_MODULES = [
    "sharedLogic",
    "sharedUI",
    "core/data",
    "core/ids",
    "core/runtime",
    "core/schemas",
    "plugin-api",
    "plugin-view",
    "avatar/core",
    "avatar/renderer-rive",
    "drawbox",
]


def production_files(repo: Path, module_globs: list[str]) -> list[Path]:
    """Every production .kt file under the modules (test source sets and build outputs excluded)."""
    root = repo / ANDROID_COMPOSE
    files: list[Path] = []
    for pattern in module_globs:
        for module in sorted(root.glob(pattern)):
            src = module / "src"
            if not src.is_dir():
                continue
            for source_set in sorted(p for p in src.iterdir() if p.is_dir()):
                if source_set.name in TEST_SOURCE_SETS or source_set.name.endswith("Test"):
                    continue
                files.extend(sorted(source_set.rglob("*.kt")))
    return [f for f in files if "/build/" not in f.as_posix()]


def platform_files(repo: Path) -> dict[str, list[Path]]:
    return {platform: production_files(repo, globs) for platform, globs in PLATFORM_MODULES.items()}


def shared_files(repo: Path) -> list[Path]:
    return production_files(repo, SHARED_MODULES)


def rel(repo: Path, path: Path) -> str:
    return path.relative_to(repo).as_posix()


# --- lexing --------------------------------------------------------------------------------

def _skip_string(text: str, i: int) -> int:
    """i points at the opening quote; returns the index just past the closing quote."""
    raw = text.startswith('"""', i)
    i += 3 if raw else 1
    n = len(text)
    while i < n:
        if raw and text.startswith('"""', i):
            i += 3
            while i < n and text[i] == '"':  # """ followed by extra quotes
                i += 1
            return i
        c = text[i]
        if not raw and c == "\\":
            i += 2
            continue
        if not raw and c == '"':
            return i + 1
        if c == "\n" and not raw:
            return i + 1  # unterminated: stop at the line end
        if c == "$" and i + 1 < n and text[i + 1] == "{":
            i = _skip_balanced(text, i + 1, "{", "}")
            continue
        i += 1
    return n


def _skip_char(text: str, i: int) -> int:
    j = i + 1
    if j < len(text) and text[j] == "\\":
        j += 2
    else:
        j += 1
    end = text.find("'", j)
    return len(text) if end < 0 else end + 1


def _skip_comment(text: str, i: int) -> int:
    if text.startswith("//", i):
        end = text.find("\n", i)
        return len(text) if end < 0 else end
    # block comments nest in Kotlin
    depth = 0
    n = len(text)
    while i < n:
        if text.startswith("/*", i):
            depth += 1
            i += 2
        elif text.startswith("*/", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
        else:
            i += 1
    return n


def _skip_balanced(text: str, i: int, open_c: str, close_c: str) -> int:
    """i points at open_c; returns the index just past its matching close_c."""
    depth = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i = _skip_string(text, i)
            continue
        if c == "'":
            i = _skip_char(text, i)
            continue
        if c == "/" and i + 1 < n and text[i + 1] in "/*":
            i = _skip_comment(text, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def strip_comments(text: str) -> str:
    """Comments replaced by spaces (newlines kept), strings kept verbatim."""
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = _skip_string(text, i)
            out.append(text[i:j])
            i = j
        elif c == "'":
            j = _skip_char(text, i)
            out.append(text[i:j])
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] in "/*":
            j = _skip_comment(text, i)
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def split_top_level(text: str, sep: str = ",") -> list[str]:
    parts: list[str] = []
    depth = 0
    angle = 0
    start = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i = _skip_string(text, i)
            continue
        if c == "'":
            i = _skip_char(text, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "<" and depth == 0 and i + 1 < n and text[i + 1] not in "=":
            angle += 1
        elif c == ">" and depth == 0 and angle > 0 and text[i - 1] != "-":
            angle -= 1
        elif c == sep and depth == 0 and angle == 0:
            parts.append(text[start:i])
            start = i + 1
        i += 1
    tail = text[start:]
    if tail.strip():
        parts.append(tail)
    return [p.strip() for p in parts if p.strip()]


# --- declarations ---------------------------------------------------------------------------

@dataclass
class Param:
    name: str
    type: str
    has_default: bool


@dataclass
class SharedClass:
    name: str
    path: str
    params: list[Param] = field(default_factory=list)


_CLASS_HEAD = re.compile(
    r"(?m)^(?P<indent>[ \t]*)(?P<mods>(?:@[\w.]+(?:\([^)]*\))?\s+|(?:public|internal|private|protected|data|value|"
    r"open|abstract|sealed|inner|annotation|enum|expect|actual|final)\s+)*)class\s+(?P<name>[A-Z]\w*)"
)
_PARAM_MODS = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s*|(?:override|private|internal|public|protected|val|var|vararg|crossinline|noinline)\s+)*")


def parse_params(param_text: str) -> list[Param]:
    params = []
    for raw in split_top_level(param_text):
        body = _PARAM_MODS.sub("", raw.strip())
        head = body
        has_default = False
        # first top-level '=' splits name:type from the default
        eq = _top_level_index(body, "=")
        if eq >= 0:
            head = body[:eq]
            has_default = True
        if ":" not in head:
            continue
        name, type_ = head.split(":", 1)
        params.append(Param(name.strip().strip("`"), " ".join(type_.split()), has_default))
    return params


def _top_level_index(text: str, char: str) -> int:
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i = _skip_string(text, i)
            continue
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            if not (c == ">" and i > 0 and text[i - 1] == "-"):
                depth -= 1
        elif c == char and depth == 0 and text[i + 1 : i + 2] != "=" and text[i - 1 : i] not in "!<>=":
            return i
        i += 1
    return -1


def shared_classes(repo: Path, files: list[Path]) -> dict[str, list[SharedClass]]:
    """Public top-level and nested classes with a primary constructor, by simple name."""
    found: dict[str, list[SharedClass]] = {}
    for path in files:
        text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
        for m in _CLASS_HEAD.finditer(text):
            mods = m.group("mods")
            if re.search(r"\b(private|internal|annotation|enum|sealed|abstract)\b", mods):
                continue
            i = m.end()
            # skip type parameters
            while i < len(text) and text[i] in " \t":
                i += 1
            if i < len(text) and text[i] == "<":
                depth = 0
                while i < len(text):
                    if text[i] == "<":
                        depth += 1
                    elif text[i] == ">":
                        depth -= 1
                        if depth == 0:
                            i += 1
                            break
                    i += 1
            ctor = re.match(r"\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|internal|private|protected)\s+)?(?:constructor\s*)?\(", text[i:])
            if not ctor:
                continue
            if re.search(r"(?:internal|private|protected)\s+constructor", ctor.group(0)):
                continue
            open_idx = i + ctor.end() - 1
            close_idx = _skip_balanced(text, open_idx, "(", ")")
            params = parse_params(text[open_idx + 1 : close_idx - 1])
            found.setdefault(m.group("name"), []).append(SharedClass(m.group("name"), rel(repo, path), params))
    return found


@dataclass
class Call:
    path: str
    line: int
    named: dict[str, str]
    positional: list[str]
    trailing_lambda: str | None


def constructor_calls(repo: Path, path: Path, names: set[str]) -> list[tuple[str, Call]]:
    """`Name(...)` calls of any of [names] in [path] (declarations excluded)."""
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    if not any(n in text for n in names):
        return []
    pattern = re.compile(r"(?<![\w.$])(" + "|".join(sorted(map(re.escape, names))) + r")\s*(?:<[^()]*?>)?\s*\(")
    out = []
    for m in pattern.finditer(text):
        before = text[max(0, m.start() - 40) : m.start()]
        if re.search(r"\b(class|fun|interface|object|typealias|import)\s+$", before) or before.rstrip().endswith("::"):
            continue
        open_idx = m.end() - 1
        close_idx = _skip_balanced(text, open_idx, "(", ")")
        args = split_top_level(text[open_idx + 1 : close_idx - 1])
        named: dict[str, str] = {}
        positional: list[str] = []
        for arg in args:
            nm = re.match(r"^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$", arg, re.S)
            if nm:
                named[nm.group(1)] = nm.group(2).strip()
            else:
                positional.append(arg)
        trailing = None
        rest = text[close_idx:]
        tm = re.match(r"[ \t]*\{", rest)
        if tm:
            lam_open = close_idx + tm.end() - 1
            trailing = text[lam_open : _skip_balanced(text, lam_open, "{", "}")]
        line = text.count("\n", 0, m.start()) + 1
        out.append((m.group(1), Call(rel(repo, path), line, named, positional, trailing)))
    return out


_TYPE_DECL = re.compile(
    r"(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|protected|data|value|open|abstract|sealed|inner|"
    r"annotation|enum|expect|actual|final|fun|external)\s+)*(?:class|interface|object|typealias)\s+([A-Z]\w*)"
)


def declared_type_names(path: Path) -> list[str]:
    text = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    return _TYPE_DECL.findall(text)
