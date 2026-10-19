#!/usr/bin/env python3
"""Require meaningful module/package overviews for every published source package."""
import argparse
from pathlib import Path
import re


def code_without_comments(text, mask_strings=False):
    """Mask comments (including Kotlin nesting), preserving newlines and literals."""
    token = re.compile(r'/\*|//[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    output, offset = [], 0
    for_match = token.search(text, offset)
    while for_match:
        start, end = for_match.span()
        value = for_match[0]
        output.append(text[offset:start])
        if value == "/*":
            end = block_comment_end(text, end)
            value = text[start:end]
        masked = value.startswith(("/*", "//")) or mask_strings
        output.append(re.sub(r"[^\n]", " ", value) if masked else value)
        offset = end
        for_match = token.search(text, offset)
    return "".join(output) + text[offset:]


def block_comment_end(text, offset):
    depth = 1
    for match in re.finditer(r"/\*|\*/", text[offset:]):
        depth += 1 if match[0] == "/*" else -1
        if depth == 0:
            return offset + match.end()
    raise ValueError("Unterminated source block comment")


def markdown_prose(text):
    """Ignore commented and fenced examples rather than treating them as coverage."""
    text = re.sub(r"<!--[\s\S]*?-->", "", text)
    fence = None
    lines = []
    for line in text.splitlines():
        match = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line)
        if fence:
            if match and match[1][0] == fence[0] and len(match[1]) >= len(fence) and not match[2].strip():
                fence = None
        elif match:
            fence = match[1]
        else:
            lines.append(line)
    if fence:
        raise ValueError("Unclosed Markdown fence in overview")
    return "\n".join(lines) + "\n"


def sections(path):
    text = markdown_prose(path.read_text(encoding="utf-8-sig"))
    chunks = re.split(r"(?m)^# (Module|Package) ([^\n]+)\n", text)
    result = {}
    for offset in range(1, len(chunks), 3):
        kind, name, body = chunks[offset:offset + 3]
        key = (kind, name.strip())
        if key in result:
            raise ValueError(f"{path}: duplicate {kind} {name}")
        if not has_introductory_paragraph(body):
            raise ValueError(f"{path}: {kind} {name} needs an introductory paragraph")
        result[key] = body
    return result


def has_introductory_paragraph(body):
    prose = re.split(r"\n\s*\n", body.strip())[0]
    return not prose.startswith("#") and len(prose.split()) >= 10


def published_modules(root):
    build = code_without_comments((root / "android-compose/build.gradle.kts").read_text(encoding="utf-8"))
    declaration = re.compile(r'\badd\(\s*"dokka"\s*,\s*project\(\s*"(:[\w:-]+)"\s*\)\s*\)')
    modules = declaration.findall(build)
    # Fail rather than silently cover a subset after the aggregate DSL changes.
    remainder = declaration.sub("", build)
    if not modules or re.search(r'"dokka"|\bdokka\s*\(', remainder):
        raise ValueError("Unsupported Dokka aggregate dependency DSL; update package discovery")
    if len(modules) != len(set(modules)):
        raise ValueError("Duplicate Dokka aggregate dependency")
    return sorted(modules)


def verify_standard_source_roots(module):
    """Do not silently skip manually configured source directories."""
    build_file = module / "build.gradle.kts"
    if build_file.exists():
        build = code_without_comments(build_file.read_text(encoding="utf-8"), mask_strings=True)
        if re.search(r"\b(?:srcDirs?|setSrcDirs)\b", build):
            raise ValueError(f"{build_file}: custom source roots require updating package discovery")


def declared_package(path):
    text = code_without_comments(path.read_text(encoding="utf-8-sig"), mask_strings=True)
    matches = re.findall(r"(?m)^\s*package\s+([\w.]+)[ \t]*(?:;|(?=\n|$))", text)
    if len(matches) != 1:
        raise ValueError(f"{path}: expected one named package declaration")
    return matches[0]


def source_packages(module):
    verify_standard_source_roots(module)
    return {declared_package(path) for path in production_source_files(module)}


def is_production_source_set(path):
    if not path.is_dir() or "test" in path.name.lower():
        return False
    if re.fullmatch(r"main|.+Main|jvmAndAndroid", path.name):
        return True
    if language_source_files(path):
        raise ValueError(f"{path}: unrecognized production source set; update package discovery")
    return False


def production_source_sets(module):
    # Includes the configured jvmAndAndroid intermediate source set and plain
    # JVM main, alongside KMP *Main. Test source sets never enter the publication.
    sources = sorted(path for path in module.glob("src/*") if is_production_source_set(path))
    if not sources:
        raise ValueError(f"{module}: no production source sets found")
    return sources


def language_source_files(folder):
    """Discover language inputs without admitting resources or generated build output."""
    return sorted(path for path in folder.rglob("*") if path.suffix in (".kt", ".java"))


def production_source_files(module):
    """Traverse configured-style production source sets, including intermediate sets."""
    for source in production_source_sets(module):
        yield from language_source_files(source / "kotlin")
        yield from language_source_files(source / "java")


def module_directory(root, name):
    return root / "android-compose" / name.lstrip(":").replace(":", "/")


def package_overview_problems(overview, entries, packages):
    declared = {package for kind, package in entries if kind == "Package"}
    missing = [f"{overview}: missing # Package {package}" for package in sorted(packages - declared)]
    stale = [f"{overview}: stale package {package}" for package in sorted(declared - packages)]
    return missing + stale


def check_module_overview(name, module):
    """Validate one overview against that module's actual production packages."""
    overview = module / "Module.md"
    try:
        entries = sections(overview)
    except (OSError, ValueError) as error:
        return 0, [str(error)]
    try:
        packages = source_packages(module) if name != "Letta Mobile API" else set()
    except (OSError, ValueError) as error:
        return 0, [str(error)]
    problems = package_overview_problems(overview, entries, packages)
    if ("Module", name) not in entries:
        problems.insert(0, f"{overview}: missing # Module {name}")
    declared_modules = {declared for kind, declared in entries if kind == "Module"}
    problems.extend(f"{overview}: unexpected # Module {declared}" for declared in sorted(declared_modules - {name}))
    return len(packages), problems


def check(root):
    problems = []
    total = 0
    published = published_modules(root)
    modules = [("Letta Mobile API", root / "android-compose")]
    modules += [(name, module_directory(root, name)) for name in published]
    for name, module in modules:
        count, module_problems = check_module_overview(name, module)
        total += count
        problems.extend(module_problems)
    return total, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        count, problems = check(args.root)
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + "\n")
    if problems:
        parser.exit(1, "\n".join(problems) + "\n")
    print(f"API overviews: {count} production packages covered")


if __name__ == "__main__":
    main()
