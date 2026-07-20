#!/usr/bin/env python3
"""Extract a bounded, advisory JSONL graph seam from a SCIP index."""

import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

SCHEMA = "letta.architecture.graph.v1"


def emit(handle, record, state):
    """Write one deterministic JSONL record while enforcing the output bound."""
    if state[0] >= state[1]:
        raise RuntimeError(f"record limit exceeded ({state[1]})")
    handle.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
    state[0] += 1


def first(value, *names, default=None):
    """Return the first present field among SCIP JSON naming variants."""
    for name in names:
        if name in value:
            return value[name]
    return default


def parse_args(argv=None):
    """Parse and validate extractor command-line arguments."""
    parser = argparse.ArgumentParser()
    parser.add_argument("index", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--max-records", type=int, default=20000)
    parser.add_argument("--scip-command", default="scip")
    args = parser.parse_args(argv)
    if args.max_records <= 0:
        parser.error("--max-records must be positive")
    if not args.index.is_file():
        parser.error(f"index does not exist: {args.index}")
    return args


def run_scip(command, index):
    """Run the SCIP printer and return its completed process and exit status."""
    try:
        result = subprocess.run(
            [command, "print", "--json", str(index)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return result, 0
    except FileNotFoundError:
        print("extract-scip-graph: scip CLI is required on PATH", file=sys.stderr)
        return None, 69
    except subprocess.CalledProcessError as error:
        print(error.stderr.rstrip(), file=sys.stderr)
        return None, error.returncode


def parse_documents(raw_json):
    """Decode SCIP JSON and validate the container types used by extraction."""
    try:
        payload = json.loads(raw_json)
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid scip JSON: {error}") from error
    if not isinstance(payload, dict):
        raise ValueError("scip JSON root must be an object")
    documents = first(payload, "documents", default=[])
    if not isinstance(documents, list):
        raise ValueError("scip JSON documents must be an array")
    return documents


def symbol_records(file_id, symbols, declared):
    """Yield unique symbol nodes and declaration edges for one document."""
    for symbol in symbols:
        symbol_id = first(symbol, "symbol", default="")
        if not symbol_id or symbol_id in declared:
            continue
        declared.add(symbol_id)
        yield {
            "schema": SCHEMA,
            "type": "node",
            "id": symbol_id,
            "kind": "symbol",
            "display": first(symbol, "displayName", "display_name", default=symbol_id),
        }
        yield {"schema": SCHEMA, "type": "edge", "from": file_id, "to": symbol_id, "kind": "declares"}


def reference_records(file_id, occurrences):
    """Yield reference edges while excluding SCIP declaration occurrences."""
    for occurrence in occurrences:
        symbol_id = first(occurrence, "symbol", default="")
        roles = int(first(occurrence, "symbolRoles", "symbol_roles", default=0))
        if not symbol_id or roles & 1:
            continue
        yield {
            "schema": SCHEMA,
            "type": "edge",
            "from": file_id,
            "to": symbol_id,
            "kind": "references",
            "range": first(occurrence, "range", default=[]),
        }


def emit_documents(handle, documents, state):
    """Emit graph records for all SCIP documents."""
    emit(handle, {"schema": SCHEMA, "type": "meta", "source": "scip-java", "advisory": True}, state)
    declared = set()
    for document in documents:
        path = first(document, "relativePath", "relative_path", default="")
        file_id = f"file:{path}"
        emit(handle, {
            "schema": SCHEMA, "type": "node", "id": file_id, "kind": "file", "path": path,
        }, state)
        records = symbol_records(file_id, first(document, "symbols", default=[]), declared)
        for record in records:
            emit(handle, record, state)
        for record in reference_records(file_id, first(document, "occurrences", default=[])):
            emit(handle, record, state)


def write_graph(output, documents, max_records):
    """Write graph JSONL to a temporary file and atomically replace output."""
    output.parent.mkdir(parents=True, exist_ok=True)
    state = [0, max_records]
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=output.parent,
            prefix=f".{output.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temporary = pathlib.Path(handle.name)
            emit_documents(handle, documents, state)
        temporary.replace(output)
    except (OSError, RuntimeError, TypeError, ValueError):
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        raise
    return state[0]


def main(argv=None):
    """Extract an advisory bounded JSONL graph from a SCIP index."""
    args = parse_args(argv)
    result, status = run_scip(args.scip_command, args.index)
    if result is None:
        return status
    try:
        documents = parse_documents(result.stdout)
        count = write_graph(args.output, documents, args.max_records)
    except (OSError, RuntimeError, TypeError, ValueError) as error:
        print(f"extract-scip-graph: {error}", file=sys.stderr)
        return 75 if not isinstance(error, ValueError) or "scip JSON" not in str(error) else 65
    print(f"extract-scip-graph: wrote {count} advisory records to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
