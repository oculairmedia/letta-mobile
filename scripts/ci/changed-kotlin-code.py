#!/usr/bin/env python3
"""Select changed Kotlin files, excluding changes limited to comments/whitespace."""
import argparse
from pathlib import Path
import re
import subprocess


TOKEN = re.compile(r"[\w$]+|===|!==|>>>|<<|>>|==|!=|<=|>=|&&|\|\||\+\+|--|->|::|\?\.|\?:|\.\.<|\.\.|[^\s]", re.UNICODE)


def comment_end(text, position):
    depth = 1
    position += 2
    while position < len(text) and depth:
        if text.startswith("/*", position):
            depth += 1
            position += 2
        elif text.startswith("*/", position):
            depth -= 1
            position += 2
        else:
            position += 1
    if depth:
        raise ValueError("Unterminated Kotlin comment")
    return position


def template_end(text, position):
    depth = 1
    while position < len(text):
        if text.startswith("/*", position):
            position = comment_end(text, position)
        elif text.startswith("//", position):
            end = text.find("\n", position)
            position = len(text) if end < 0 else end
        elif text[position] in "\"'`":
            position = literal_end(text, position)
        else:
            depth += (text[position] == "{") - (text[position] == "}")
            position += 1
            if depth == 0:
                return position
    raise ValueError("Unterminated Kotlin template")


def literal_end(text, position):
    delimiter = '"""' if text.startswith('"""', position) else text[position]
    position += len(delimiter)
    while position < len(text):
        if text.startswith(delimiter, position):
            return position + len(delimiter)
        if delimiter in ('"', '"""') and text.startswith('${', position):
            position = template_end(text, position + 2)
        else:
            position += 2 if delimiter in ('"', "'") and text[position] == "\\" else 1
    raise ValueError("Unterminated Kotlin literal")


def code_tokens(text):
    """Keep literals opaque; malformed input fails closed and stays in scope."""
    tokens = []
    position = 0
    separator = ""
    while position < len(text):
        if text[position].isspace():
            separator = "\n" if text[position] in "\r\n" or separator == "\n" else " "
            position += 1
        elif text.startswith("//", position):
            end = text.find("\n", position)
            position = len(text) if end < 0 else end
        elif text.startswith("/*", position):
            start = position
            position = comment_end(text, position)
            separator = "\n" if "\n" in text[start:position] or separator == "\n" else " "
        elif text[position] in "\"'`":
            if tokens and separator:
                tokens.append(separator)
            separator = ""
            start = position
            position = literal_end(text, position)
            tokens.append(text[start:position])
        else:
            if tokens and separator:
                tokens.append(separator)
            separator = ""
            match = TOKEN.match(text, position)
            tokens.append(match.group())
            position = match.end()
    return tokens


def git(root, *arguments, input=None):
    return subprocess.run(["git", *arguments], cwd=root, check=True, capture_output=True, input=input).stdout


def tree_blobs(data):
    """NUL-delimited tree paths may contain tabs or newlines; object IDs cannot."""
    blobs = {}
    for record in data.split(b"\0"):
        if not record:
            continue
        metadata, name = record.split(b"\t", 1)
        _, kind, object_id = metadata.split()
        if kind == b"blob":
            if not re.fullmatch(rb"[0-9a-f]{40}|[0-9a-f]{64}", object_id):
                raise ValueError("Invalid tree blob ID")
            blobs[name.decode("utf-8")] = object_id
    return blobs


def read_blobs(root, object_ids):
    """Read immutable objects once, with byte counts separating arbitrary source contents."""
    requested = sorted(set(object_ids))
    if not requested:
        return {}
    if any(not re.fullmatch(rb"[0-9a-f]{40}|[0-9a-f]{64}", value) for value in requested):
        raise ValueError("Expected immutable blob IDs")
    output = git(root, "cat-file", "--batch", input=b"\n".join(requested) + b"\n")
    blobs = {}
    offset = 0
    for object_id in requested:
        end = output.find(b"\n", offset)
        header = output[offset:end].split() if end >= 0 else []
        if len(header) != 3 or header[:2] != [object_id, b"blob"] or not header[2].isdigit():
            raise ValueError("Unexpected batch blob header")
        start = end + 1
        offset = start + int(header[2])
        if offset >= len(output) or output[offset:offset + 1] != b"\n":
            raise ValueError("Truncated batch blob contents")
        blobs[object_id] = output[start:offset]
        offset += 1
    if offset != len(output):
        raise ValueError("Unexpected trailing batch output")
    return blobs


def changed_files(root, base):
    commit = git(root, "rev-parse", "--verify", "--end-of-options", base + "^{commit}").decode().strip()
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", commit):
        raise ValueError("Expected a full base commit ID")
    ancestor = git(root, "merge-base", commit, "HEAD").decode().strip()
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", ancestor):
        raise ValueError("Expected a full merge-base commit ID")
    names = set()
    for arguments in (("diff", "--name-only", "-z", ancestor, "HEAD", "--"),
                      ("diff", "--name-only", "-z", "HEAD", "--"),
                      ("ls-files", "--others", "--exclude-standard", "-z")):
        names.update(git(root, *arguments).decode("utf-8").split("\0"))
    tracked = tree_blobs(git(root, "ls-tree", "-r", "-z", ancestor))
    candidates = []
    for name in sorted(names):
        path = root / name
        if not name.startswith("android-compose/") or not name.endswith(".kt") or not path.is_file():
            continue
        if any(part in path.parts for part in ("build", "generated")) or name.startswith(("android-compose/drawbox/", "android-compose/quality/detekt-rules/")):
            continue
        candidates.append(name)
    blobs = read_blobs(root, (tracked[name] for name in candidates if name in tracked))
    for name in candidates:
        path = root / name
        if name in tracked:
            old = blobs[tracked[name]].decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
            try:
                if code_tokens(old) == code_tokens(path.read_text(encoding="utf-8-sig")):
                    continue
            except ValueError:
                pass  # Broken syntax must reach the Kotlin checks.
        yield name


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main")
    args = parser.parse_args()
    print("\n".join(changed_files(Path(__file__).resolve().parents[2], args.base)))
