# Architecture Query MCP

A dependency-free Python 3.11+ utility that validates the five-file architecture JSONL contract, imports it atomically into a versioned SQLite database, and serves compact deterministic queries over CLI or MCP stdio. It does not use a service database, network access, embeddings, or exporter code.

## Import and query

All filesystem arguments must be absolute. Changed/source paths passed to tools must be repository-relative and cannot contain `..`.

```bash
python3 tools/architecture_query/architecture_query.py import \
  --contract-dir "$PWD/reports/architecture" \
  --db "$PWD/reports/architecture.db"

python3 tools/architecture_query/architecture_query.py query \
  --db "$PWD/reports/architecture.db" \
  module_deps '{"module":":app","transitive":true,"limit":25}'
```

Use `--help` on the root command or any subcommand for complete CLI help. Results default to 50 entries and reject limits above 200.

## MCP stdio

Configure an MCP client to execute:

```bash
python3 /absolute/repo/tools/architecture_query/architecture_query.py serve \
  --db /absolute/path/architecture.db
```

The server implements newline-delimited JSON-RPC for `initialize`, `ping`, `tools/list`, and `tools/call`. Tools are `get_module`, `module_deps`, `module_reverse_deps`, `source_set_owners`, `change_impact`, `architecture_violations`, and `lookup`. `lookup` is the intentionally narrow text/symbol seam for a later local SCIP backend; today it searches module contract metadata only.

The importer accepts common field aliases but keeps the source object in each row. The standalone fixture under `tests/fixtures/contract` specifies supported semantics without depending on exporter implementation.

## Tests

```bash
python3 -m unittest discover -s tools/architecture_query/tests -v
```
