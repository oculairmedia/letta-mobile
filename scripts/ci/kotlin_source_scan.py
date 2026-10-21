"""Small Kotlin source reader shared by the parity guardrails (letta-mobile-ewtaq).

It is deliberately not a parser: a tokenizer that knows Kotlin comments (nested), strings (with
templates), char literals and brackets is enough to find primary constructors, constructor calls
and top-level declarations in production sources without compiling them.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

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


def _is_production_set(source_set: Path) -> bool:
    return source_set.is_dir() and source_set.name not in TEST_SOURCE_SETS and not source_set.name.endswith("Test")


def _module_sources(module: Path) -> list[Path]:
    src = module / "src"
    if not src.is_dir():
        return []
    sets = sorted(p for p in src.iterdir() if _is_production_set(p))
    return [f for source_set in sets for f in sorted(source_set.rglob("*.kt")) if "/build/" not in f.as_posix()]


def production_files(repo: Path, module_globs: list[str]) -> list[Path]:
    """Every production .kt file under the modules (test source sets and build outputs excluded)."""
    root = repo / ANDROID_COMPOSE
    return [f for pattern in module_globs for module in sorted(root.glob(pattern)) for f in _module_sources(module)]


def platform_files(repo: Path) -> dict[str, list[Path]]:
    return {platform: production_files(repo, globs) for platform, globs in PLATFORM_MODULES.items()}


def shared_files(repo: Path) -> list[Path]:
    return production_files(repo, SHARED_MODULES)


def rel(repo: Path, path: Path) -> str:
    return path.relative_to(repo).as_posix()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


# --- tokens ---------------------------------------------------------------------------------

@dataclass(frozen=True)
class Token:
    kind: str  # code | open | close | angle-open | angle-close | string | char | comment
    start: int
    end: int


_MASTER = re.compile(
    r"(?P<comment>//[^\n]*)|(?P<block>/\*)|(?P<string>\"\"\"|\")|(?P<char>'(?:\\.|[^'\\\n])*'?)"
    r"|(?P<open>[(\[{])|(?P<close>[)\]}])|(?P<code>->|<=|>=|[<>]|[^/\"'()\[\]{}<>]+|/)"
)
_STRING_STOPS = {True: re.compile(r'"""|\$\{'), False: re.compile(r'\\.|"|\n|\$\{')}
_BLOCK_STOPS = re.compile(r"/\*|\*/")


def _string_end(text: str, start: int) -> int:
    """[start] is the opening quote; the index just past the closing one (templates skipped)."""
    raw = text.startswith('"""', start)
    stops = _STRING_STOPS[raw]
    i = start + (3 if raw else 1)
    while (m := stops.search(text, i)) is not None:
        stop = m.group(0)
        if stop == "${":
            i = balanced_end(text, m.end() - 1)
        elif stop.startswith("\\"):
            i = m.end()
        else:
            return m.end()
    return len(text)


def _block_comment_end(text: str, start: int) -> int:
    depth = 0
    for m in _BLOCK_STOPS.finditer(text, start):
        depth += 1 if m.group(0) == "/*" else -1
        if depth == 0:
            return m.end()
    return len(text)


def _code_kind(lexeme: str) -> str:
    return {"<": "angle-open", ">": "angle-close"}.get(lexeme, "code")


def tokens(text: str, start: int = 0) -> Iterator[Token]:
    i = start
    while i < len(text):
        m = _MASTER.match(text, i)
        kind = m.lastgroup
        end = m.end()
        if kind == "string":
            end = _string_end(text, i)
        elif kind == "block":
            kind, end = "comment", _block_comment_end(text, i)
        elif kind == "code":
            kind = _code_kind(m.group(0))
        yield Token(kind, i, end)
        i = end


def balanced_end(text: str, open_idx: int) -> int:
    """[open_idx] is an opening bracket; the index just past its matching close."""
    depth = 0
    for token in tokens(text, open_idx):
        depth += {"open": 1, "close": -1}.get(token.kind, 0)
        if depth == 0:
            return token.end
    return len(text)


def strip_comments(text: str) -> str:
    """Comments replaced by blanks (newlines kept); strings kept verbatim."""
    return "".join(_blank(text[t.start : t.end]) if t.kind == "comment" else text[t.start : t.end] for t in tokens(text))


def _blank(chunk: str) -> str:
    return re.sub(r"[^\n]", " ", chunk)


def without_bodies(text: str) -> str:
    """[text] (comments already stripped) with everything inside braces blanked: its top level."""
    out = []
    depth = 0
    for token in tokens(text):
        chunk = text[token.start : token.end]
        depth -= 1 if chunk == "}" else 0
        out.append(chunk if depth == 0 else _blank(chunk))
        depth += 1 if chunk == "{" else 0
    return "".join(out)


_BRACKET_STEP = {"open": 1, "close": -1}
_ANGLE_STEP = {"angle-open": 1, "angle-close": -1}


def _separators(chunk: str, sep: str) -> list[int]:
    if sep == "=":
        return [m.start() for m in re.finditer(r"(?<![=!<>])=(?!=)", chunk)]
    return [i for i, c in enumerate(chunk) if c == sep]


def split_top_level(text: str, sep: str = ",") -> list[str]:
    """[text] split at [sep] outside brackets, strings and type arguments."""
    cuts = []
    depth = angle = 0
    for token in tokens(text):
        depth += _BRACKET_STEP.get(token.kind, 0)
        angle = max(0, angle + _ANGLE_STEP.get(token.kind, 0) * (depth == 0))
        if depth == angle == 0 and token.kind == "code":
            cuts += [token.start + offset for offset in _separators(text[token.start : token.end], sep)]
    bounds = zip([-1, *cuts], [*cuts, len(text)])
    return [part for part in (text[a + 1 : b].strip() for a, b in bounds) if part]


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
    r"(?m)^[ \t]*(?P<mods>(?:@[\w.]+(?:\([^)]*\))?\s+|(?:public|internal|private|protected|data|value|"
    r"open|abstract|sealed|inner|annotation|enum|expect|actual|final)\s+)*)class\s+(?P<name>[A-Z]\w*)"
    r"\s*(?:<[^(){}]*?>)?"
)
_NOT_CONSTRUCTIBLE = re.compile(r"\b(private|internal|annotation|enum|sealed|abstract)\b")
_PRIMARY_CTOR = re.compile(r"\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?P<vis>(?:public|internal|private|protected)\s+)?(?:constructor\s*)?\(")
_HIDDEN_CTOR = {"internal", "private", "protected"}
_PARAM_MODS = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s*|(?:override|private|internal|public|protected|val|var|vararg|crossinline|noinline)\s+)*")


def parse_param(raw: str) -> Param | None:
    body = _PARAM_MODS.sub("", raw.strip())
    pieces = split_top_level(body, "=")
    head = pieces[0] if pieces else body
    if ":" not in head:
        return None
    name, type_ = head.split(":", 1)
    return Param(name.strip().strip("`"), " ".join(type_.split()), len(pieces) > 1)


def parse_params(param_text: str) -> list[Param]:
    return [p for p in map(parse_param, split_top_level(param_text)) if p is not None]


def _primary_constructor(text: str, after_name: int) -> list[Param] | None:
    ctor = _PRIMARY_CTOR.match(text, after_name)
    if ctor is None or (ctor.group("vis") or "").strip() in _HIDDEN_CTOR:
        return None
    open_idx = ctor.end() - 1
    return parse_params(text[open_idx + 1 : balanced_end(text, open_idx) - 1])


def classes_in(text: str) -> Iterator[tuple[str, list[Param]]]:
    """(name, primary constructor params) of each constructible class in [text]."""
    for m in _CLASS_HEAD.finditer(text):
        params = None if _NOT_CONSTRUCTIBLE.search(m.group("mods")) else _primary_constructor(text, m.end())
        if params is not None:
            yield m.group("name"), params


def shared_classes(repo: Path, files: list[Path]) -> dict[str, list[SharedClass]]:
    """Public top-level and nested classes with a primary constructor, by simple name."""
    found: dict[str, list[SharedClass]] = {}
    for path in files:
        for name, params in classes_in(strip_comments(read(path))):
            found.setdefault(name, []).append(SharedClass(name, rel(repo, path), params))
    return found


@dataclass
class Call:
    path: str
    line: int
    named: dict[str, str]
    positional: list[str]
    trailing_lambda: str | None


_NAMED_ARG = re.compile(r"^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$", re.S)
_DECLARATION_BEFORE = re.compile(r"\b(class|fun|interface|object|typealias|import)\s+$|::$")
_TRAILING_LAMBDA = re.compile(r"[ \t]*\{")


def _arguments(text: str, open_idx: int, close_idx: int) -> tuple[dict[str, str], list[str]]:
    args = split_top_level(text[open_idx + 1 : close_idx - 1])
    matches = [(arg, _NAMED_ARG.match(arg)) for arg in args]
    named = {m.group(1): m.group(2).strip() for _, m in matches if m}
    return named, [arg for arg, m in matches if not m]


def _trailing_lambda(text: str, close_idx: int) -> str | None:
    m = _TRAILING_LAMBDA.match(text, close_idx)
    return None if m is None else text[m.end() - 1 : balanced_end(text, m.end() - 1)]


def _call_pattern(names: set[str]) -> re.Pattern[str]:
    return re.compile(r"(?<![\w.$])(" + "|".join(sorted(map(re.escape, names))) + r")\s*(?:<[^()]*?>)?\s*\(")


def _call_at(repo: Path, path: Path, text: str, m: re.Match[str]) -> Call:
    open_idx = m.end() - 1
    close_idx = balanced_end(text, open_idx)
    named, positional = _arguments(text, open_idx, close_idx)
    line = text.count("\n", 0, m.start()) + 1
    return Call(rel(repo, path), line, named, positional, _trailing_lambda(text, close_idx))


def constructor_calls(repo: Path, path: Path, names: set[str]) -> list[tuple[str, Call]]:
    """`Name(...)` calls of any of [names] in [path] (declarations excluded)."""
    text = strip_comments(read(path))
    if not any(n in text for n in names):
        return []
    return [
        (m.group(1), _call_at(repo, path, text, m))
        for m in _call_pattern(names).finditer(text)
        if not _DECLARATION_BEFORE.search(text[max(0, m.start() - 40) : m.start()])
    ]


_TYPE_DECL = re.compile(
    r"(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|protected|data|value|open|abstract|sealed|inner|"
    r"annotation|enum|expect|actual|final|fun|external)\s+)*(?:class|interface|object|typealias)\s+([A-Z]\w*)"
)
_HEADER_GAP = re.compile(r"\s*(?:<[^{(:]*?>)?\s*(?:@[\w.]+\s*)*(?:(?:public|internal|private|protected)\s+)?(?:constructor\s*)?")
_COLON = re.compile(r"\s*:")


def declared_type_names(path: Path) -> list[str]:
    return _TYPE_DECL.findall(strip_comments(read(path)))


def supertypes(text: str, after_name: int) -> list[str]:
    """Simple or qualified names after a class header's ':' (its constructor skipped)."""
    i = _HEADER_GAP.match(text, after_name).end()
    if text.startswith("(", i):
        i = balanced_end(text, i)
    colon = _COLON.match(text, i)
    if colon is None:
        return []
    header = re.split(r"\{|\n\s*\n|\swhere\s", text[colon.end() : colon.end() + 600], maxsplit=1)[0]
    return [name for name in map(_supertype_name, split_top_level(header)) if name]


def _supertype_name(part: str) -> str:
    return re.split(r"[(<]|\sby\s", part, maxsplit=1)[0].strip()
