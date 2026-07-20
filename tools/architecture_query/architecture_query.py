#!/usr/bin/env python3
"""Import the architecture JSONL contract and query it locally over CLI or MCP."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
from collections import deque
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

SCHEMA_VERSION = 1
DEFAULT_LIMIT = 50
MAX_LIMIT = 200
CONTRACT_FILES = (
    "modules.jsonl",
    "source_sets.jsonl",
    "variants.jsonl",
    "project_edges.jsonl",
    "external_edges.jsonl",
)
MODULE_KEYS = ("module", "module_path", "project", "project_path", "path", "id")
SOURCE_SET_KEYS = ("source_set", "sourceSet", "name", "id")

SCHEMA = """
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE modules(
    module TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    directory TEXT,
    kind TEXT,
    payload TEXT NOT NULL
);
CREATE TABLE source_sets(
    module TEXT NOT NULL REFERENCES modules(module),
    source_set TEXT NOT NULL,
    paths TEXT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY(module, source_set)
);
CREATE TABLE variants(
    module TEXT NOT NULL REFERENCES modules(module),
    variant TEXT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY(module, variant)
);
CREATE TABLE project_edges(
    source TEXT NOT NULL REFERENCES modules(module),
    target TEXT NOT NULL REFERENCES modules(module),
    configuration TEXT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY(source, target, configuration)
);
CREATE TABLE external_edges(
    source TEXT NOT NULL REFERENCES modules(module),
    coordinate TEXT NOT NULL,
    configuration TEXT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY(source, coordinate, configuration)
);
CREATE INDEX project_edges_target_idx ON project_edges(target, source);
CREATE INDEX source_sets_module_idx ON source_sets(module, source_set);
"""


def _first(record: dict[str, Any], keys: Iterable[str], *, required: bool = False) -> Any:
    for key in keys:
        value = record.get(key)
        if value is not None and value != "":
            return value
    if required:
        raise ValueError(f"missing required field (one of: {', '.join(keys)})")
    return None


def _module_id(record: dict[str, Any]) -> str:
    value = str(_first(record, MODULE_KEYS, required=True))
    if not value.startswith(":"):
        raise ValueError(f"invalid module id {value!r}: expected Gradle path beginning with ':'")
    return value


def _edge_source(record: dict[str, Any]) -> str:
    return str(_first(record, ("source", "from", "source_module", "sourceModule", "module"), required=True))


def _edge_target(record: dict[str, Any]) -> str:
    return str(_first(record, ("target", "to", "target_module", "targetModule", "dependency"), required=True))


def _configuration(record: dict[str, Any]) -> str:
    return str(_first(record, ("configuration", "scope", "bucket")) or "")


def _compact(value: Any) -> str:
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path.name}:{line_number}: invalid JSON: {exc.msg}") from exc
            if not isinstance(record, dict):
                raise ValueError(f"{path.name}:{line_number}: expected a JSON object")
            records.append(record)
    return records


def validate_contract_dir(value: str) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise ValueError("contract directory must be an absolute path")
    path = path.resolve(strict=True)
    if not path.is_dir():
        raise ValueError(f"contract path is not a directory: {path}")
    missing = [name for name in CONTRACT_FILES if not (path / name).is_file()]
    if missing:
        raise ValueError(f"contract directory is missing: {', '.join(missing)}")
    for name in CONTRACT_FILES:
        candidate = path / name
        if candidate.is_symlink() or candidate.resolve().parent != path:
            raise ValueError(f"contract file must be a regular file inside the contract directory: {name}")
    return path


def validate_db_path(value: str, *, must_exist: bool) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise ValueError("database path must be absolute")
    if must_exist:
        if path.is_symlink():
            raise ValueError(f"database must not be a symbolic link: {path}")
        path = path.resolve(strict=True)
        if not path.is_file():
            raise ValueError(f"database must be a regular file: {path}")
    else:
        parent = path.parent.resolve(strict=True)
        if not parent.is_dir():
            raise ValueError(f"database parent is not a directory: {parent}")
        path = parent / path.name
        if path.exists() and (path.is_symlink() or not path.is_file()):
            raise ValueError(f"database must be a regular file: {path}")
    return path


def import_contract(contract_value: str, db_value: str) -> dict[str, Any]:
    contract = validate_contract_dir(contract_value)
    db_path = validate_db_path(db_value, must_exist=False)
    data = {name: _read_jsonl(contract / name) for name in CONTRACT_FILES}
    modules = {_module_id(record): record for record in data["modules.jsonl"]}
    if len(modules) != len(data["modules.jsonl"]):
        raise ValueError("modules.jsonl contains duplicate module ids")

    temp = db_path.with_name(f".{db_path.name}.{os.getpid()}.tmp")
    if temp.exists():
        temp.unlink()
    conn = sqlite3.connect(temp)
    try:
        conn.execute("PRAGMA foreign_keys=ON")
        conn.executescript(SCHEMA)
        digest = hashlib.sha256()
        for name in CONTRACT_FILES:
            digest.update(name.encode("utf-8"))
            digest.update((contract / name).read_bytes())
        conn.executemany("INSERT INTO metadata VALUES (?, ?)", [
            ("schema_version", str(SCHEMA_VERSION)),
            ("contract_sha256", digest.hexdigest()),
        ])
        for module, record in sorted(modules.items()):
            name = str(_first(record, ("name",)) or module.rsplit(":", 1)[-1])
            directory = _first(record, ("directory", "project_dir", "projectDir"))
            kind = _first(record, ("kind", "type", "plugin"))
            conn.execute("INSERT INTO modules VALUES (?, ?, ?, ?, ?)",
                         (module, name, directory, kind, _compact(record)))
        for record in data["source_sets.jsonl"]:
            module = _module_id(record)
            source_set = str(_first(record, SOURCE_SET_KEYS, required=True))
            raw_paths = _first(record, ("paths", "source_dirs", "sourceDirs", "directories")) or []
            if isinstance(raw_paths, str):
                raw_paths = [raw_paths]
            conn.execute("INSERT INTO source_sets VALUES (?, ?, ?, ?)",
                         (module, source_set, _compact(sorted(set(map(str, raw_paths)))), _compact(record)))
        for record in data["variants.jsonl"]:
            module = _module_id(record)
            variant = str(_first(record, ("variant", "name", "id"), required=True))
            conn.execute("INSERT INTO variants VALUES (?, ?, ?)", (module, variant, _compact(record)))
        for record in data["project_edges.jsonl"]:
            source, target = _edge_source(record), _edge_target(record)
            conn.execute("INSERT INTO project_edges VALUES (?, ?, ?, ?)",
                         (source, target, _configuration(record), _compact(record)))
        for record in data["external_edges.jsonl"]:
            source = _edge_source(record)
            coordinate = str(_first(record, ("coordinate", "target", "to", "dependency", "module_id"), required=True))
            conn.execute("INSERT INTO external_edges VALUES (?, ?, ?, ?)",
                         (source, coordinate, _configuration(record), _compact(record)))
        conn.commit()
        conn.close()
        os.replace(temp, db_path)
    except Exception:
        conn.close()
        temp.unlink(missing_ok=True)
        raise
    return {"schema_version": SCHEMA_VERSION, "database": str(db_path),
            "counts": {name.removesuffix(".jsonl"): len(records) for name, records in data.items()}}


class ArchitectureQuery:
    def __init__(self, db_value: str):
        self.path = validate_db_path(db_value, must_exist=True)
        self.db = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)
        self.db.row_factory = sqlite3.Row
        version = self.db.execute("SELECT value FROM metadata WHERE key='schema_version'").fetchone()
        if not version or int(version[0]) != SCHEMA_VERSION:
            actual = version[0] if version else "missing"
            raise ValueError(f"unsupported database schema {actual}; expected {SCHEMA_VERSION}; re-import the contract")

    def close(self) -> None:
        self.db.close()

    @staticmethod
    def limit(value: Any) -> int:
        limit = DEFAULT_LIMIT if value is None else int(value)
        if not 1 <= limit <= MAX_LIMIT:
            raise ValueError(f"limit must be between 1 and {MAX_LIMIT}")
        return limit

    def _module_exists(self, module: str) -> None:
        if not self.db.execute("SELECT 1 FROM modules WHERE module=?", (module,)).fetchone():
            raise ValueError(f"unknown module: {module}")

    def get_module(self, module: str) -> dict[str, Any]:
        row = self.db.execute("SELECT module,name,directory,kind,payload FROM modules WHERE module=?", (module,)).fetchone()
        if not row:
            raise ValueError(f"unknown module: {module}")
        result = dict(row)
        result["contract"] = json.loads(result.pop("payload"))
        result["source_sets"] = [r[0] for r in self.db.execute(
            "SELECT source_set FROM source_sets WHERE module=? ORDER BY source_set", (module,))]
        result["variants"] = [r[0] for r in self.db.execute(
            "SELECT variant FROM variants WHERE module=? ORDER BY variant", (module,))]
        return result

    def module_deps(self, module: str, *, reverse: bool = False, transitive: bool = False,
                    limit: Any = None) -> dict[str, Any]:
        self._module_exists(module)
        bound = self.limit(limit)
        left, right = ("target", "source") if reverse else ("source", "target")
        queue = deque([(module, 0)])
        seen = {module}
        results: list[dict[str, Any]] = []
        truncated = False
        while queue:
            current, depth = queue.popleft()
            rows = self.db.execute(
                f"SELECT {right},configuration FROM project_edges WHERE {left}=? ORDER BY {right},configuration",
                (current,),
            ).fetchall()
            for neighbor, configuration in rows:
                if neighbor in seen:
                    continue
                seen.add(neighbor)
                if len(results) >= bound:
                    truncated = True
                    queue.clear()
                    break
                results.append({"module": neighbor, "depth": depth + 1, "configuration": configuration})
                if transitive:
                    queue.append((neighbor, depth + 1))
            if not transitive:
                break
        return {"module": module, "direction": "reverse" if reverse else "forward",
                "transitive": transitive, "items": results, "truncated": truncated}

    def source_set_owners(self, path: str, *, limit: Any = None) -> dict[str, Any]:
        normalized = normalize_repo_path(path)
        bound = self.limit(limit)
        matches: list[dict[str, Any]] = []
        for row in self.db.execute("SELECT module,source_set,paths FROM source_sets ORDER BY module,source_set"):
            for root in json.loads(row["paths"]):
                root_normalized = normalize_repo_path(root)
                if normalized == root_normalized or normalized.startswith(root_normalized + "/"):
                    matches.append({"module": row["module"], "source_set": row["source_set"], "root": root_normalized})
        matches.sort(key=lambda item: (-len(item["root"]), item["module"], item["source_set"]))
        return {"path": normalized, "items": matches[:bound], "truncated": len(matches) > bound}

    def change_impact(self, paths: list[str], *, transitive: bool = True, limit: Any = None) -> dict[str, Any]:
        bound = self.limit(limit)
        if not paths:
            raise ValueError("paths must contain at least one repository-relative path")
        owners: set[str] = set()
        normalized = sorted(set(normalize_repo_path(path) for path in paths))
        for path in normalized:
            owners.update(item["module"] for item in self.source_set_owners(path, limit=MAX_LIMIT)["items"])
        impacted = set(owners)
        for owner in sorted(owners):
            impacted.update(item["module"] for item in self.module_deps(
                owner, reverse=True, transitive=transitive, limit=MAX_LIMIT)["items"])
        items = sorted(impacted)
        return {"paths": normalized, "owners": sorted(owners), "modules": items[:bound],
                "transitive": transitive, "truncated": len(items) > bound}

    def architecture_violations(self, *, limit: Any = None) -> dict[str, Any]:
        bound = self.limit(limit)
        violations: list[dict[str, Any]] = []
        rows = self.db.execute("SELECT source,target,configuration,payload FROM project_edges ORDER BY source,target,configuration")
        for source, target, configuration, payload in rows:
            record = json.loads(payload)
            allowed = record.get("allowed")
            violation = record.get("violation") or record.get("reason")
            if allowed is False or violation:
                violations.append({"source": source, "target": target, "configuration": configuration,
                                   "rule": record.get("rule"), "reason": violation or "edge marked disallowed"})
        return {"items": violations[:bound], "truncated": len(violations) > bound}

    def lookup(self, query: str, *, kind: str = "text", limit: Any = None) -> dict[str, Any]:
        bound = self.limit(limit)
        if kind not in ("text", "symbol"):
            raise ValueError("kind must be 'text' or 'symbol'")
        needle = query.strip().casefold()
        if not needle or len(needle) > 256:
            raise ValueError("query must contain 1 to 256 non-whitespace characters")
        items: list[dict[str, str]] = []
        for row in self.db.execute("SELECT module,payload FROM modules ORDER BY module"):
            payload = row["payload"]
            if needle in payload.casefold():
                items.append({"module": row["module"], "match": "module_contract"})
        # This intentionally narrow seam can later delegate symbol queries to an imported SCIP index.
        return {"query": query.strip(), "kind": kind, "backend": "architecture_contract",
                "items": items[:bound], "truncated": len(items) > bound}


def normalize_repo_path(value: str) -> str:
    if not isinstance(value, str) or not value.strip() or "\x00" in value:
        raise ValueError("path must be a non-empty repository-relative string")
    value = value.replace("\\", "/")
    path = PurePosixPath(value)
    if path.is_absolute() or re.match(r"^[A-Za-z]:", value) or ".." in path.parts:
        raise ValueError(f"path must stay repository-relative: {value!r}")
    normalized = str(path)
    if normalized in ("", "."):
        raise ValueError("path must identify a repository entry")
    return normalized.removeprefix("./")


TOOLS: dict[str, dict[str, Any]] = {
    "get_module": {"description": "Get one module with source sets and variants.", "required": ["module"]},
    "module_deps": {"description": "Get bounded direct or transitive project dependencies.", "required": ["module"]},
    "module_reverse_deps": {"description": "Get bounded direct or transitive reverse dependencies.", "required": ["module"]},
    "source_set_owners": {"description": "Find source sets owning a repository-relative path.", "required": ["path"]},
    "change_impact": {"description": "Find owner and reverse-dependent modules for changed paths.", "required": ["paths"]},
    "architecture_violations": {"description": "List contract edges explicitly marked disallowed or violating.", "required": []},
    "lookup": {"description": "Text/symbol lookup seam; currently searches module contract metadata.", "required": ["query"]},
}


def tool_definitions() -> list[dict[str, Any]]:
    properties = {
        "module": {"type": "string"}, "path": {"type": "string"},
        "paths": {"type": "array", "items": {"type": "string"}},
        "transitive": {"type": "boolean", "default": False},
        "limit": {"type": "integer", "minimum": 1, "maximum": MAX_LIMIT},
        "query": {"type": "string", "maxLength": 256},
        "kind": {"type": "string", "enum": ["text", "symbol"]},
    }
    return [{"name": name, "description": spec["description"],
             "inputSchema": {"type": "object", "properties": properties, "required": spec["required"],
                             "additionalProperties": False}}
            for name, spec in TOOLS.items()]


def call_tool(query: ArchitectureQuery, name: str, args: dict[str, Any]) -> dict[str, Any]:
    if name == "get_module":
        return query.get_module(args["module"])
    if name in ("module_deps", "module_reverse_deps"):
        return query.module_deps(args["module"], reverse=name == "module_reverse_deps",
                                 transitive=args.get("transitive", False), limit=args.get("limit"))
    if name == "source_set_owners":
        return query.source_set_owners(args["path"], limit=args.get("limit"))
    if name == "change_impact":
        return query.change_impact(args["paths"], transitive=args.get("transitive", True), limit=args.get("limit"))
    if name == "architecture_violations":
        return query.architecture_violations(limit=args.get("limit"))
    if name == "lookup":
        return query.lookup(args["query"], kind=args.get("kind", "text"), limit=args.get("limit"))
    raise ValueError(f"unknown tool: {name}")


def serve_mcp(db_value: str) -> int:
    query = ArchitectureQuery(db_value)
    try:
        for line in sys.stdin:
            request: Any = None
            try:
                request = json.loads(line)
                method = request.get("method")
                if method == "initialize":
                    result = {"protocolVersion": request.get("params", {}).get("protocolVersion", "2024-11-05"),
                              "capabilities": {"tools": {}},
                              "serverInfo": {"name": "architecture-query", "version": "1.0"}}
                elif method == "notifications/initialized":
                    continue
                elif method == "ping":
                    result = {}
                elif method == "tools/list":
                    result = {"tools": tool_definitions()}
                elif method == "tools/call":
                    params = request.get("params", {})
                    value = call_tool(query, params.get("name", ""), params.get("arguments", {}))
                    result = {"content": [{"type": "text", "text": _compact(value)}], "structuredContent": value}
                else:
                    raise ValueError(f"unsupported method: {method}")
                response = {"jsonrpc": "2.0", "id": request.get("id"), "result": result}
            except Exception as exc:
                response = {"jsonrpc": "2.0", "id": request.get("id") if isinstance(request, dict) else None,
                            "error": {"code": -32602, "message": str(exc)}}
            sys.stdout.write(_compact(response) + "\n")
            sys.stdout.flush()
    finally:
        query.close()
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Local SQLite query layer and stdio MCP server for architecture JSONL.")
    sub = parser.add_subparsers(dest="command", required=True)
    importer = sub.add_parser("import", help="validate and import the five-file JSONL contract")
    importer.add_argument("--contract-dir", required=True, help="absolute directory containing the contract files")
    importer.add_argument("--db", required=True, help="absolute SQLite output path (replaced atomically)")
    server = sub.add_parser("serve", help="serve deterministic MCP tools over newline-delimited stdio JSON-RPC")
    server.add_argument("--db", required=True, help="absolute path to an imported SQLite database")
    query_parser = sub.add_parser("query", help="call one tool and print compact JSON")
    query_parser.add_argument("--db", required=True, help="absolute path to an imported SQLite database")
    query_parser.add_argument("tool", choices=sorted(TOOLS))
    query_parser.add_argument("arguments", nargs="?", default="{}", help="tool arguments as a JSON object")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "import":
            result = import_contract(args.contract_dir, args.db)
        elif args.command == "serve":
            return serve_mcp(args.db)
        else:
            arguments = json.loads(args.arguments)
            if not isinstance(arguments, dict):
                raise ValueError("arguments must be a JSON object")
            query = ArchitectureQuery(args.db)
            try:
                result = call_tool(query, args.tool, arguments)
            finally:
                query.close()
        print(_compact(result))
        return 0
    except (ValueError, OSError, sqlite3.Error, json.JSONDecodeError) as exc:
        parser.error(str(exc))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
