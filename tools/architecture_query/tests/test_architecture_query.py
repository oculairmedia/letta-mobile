import importlib.util
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "architecture_query.py"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "contract"
spec = importlib.util.spec_from_file_location("architecture_query", SCRIPT)
architecture_query = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(architecture_query)


class ArchitectureQueryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = Path(self.temp.name) / "architecture.db"
        architecture_query.import_contract(str(FIXTURE), str(self.db))
        self.query = architecture_query.ArchitectureQuery(str(self.db))

    def tearDown(self):
        self.query.close()
        self.temp.cleanup()

    def test_module_and_dependencies_are_deterministic(self):
        module = self.query.get_module(":app")
        self.assertEqual(["main"], module["source_sets"])
        self.assertEqual(["debug", "release"], module["variants"])
        deps = self.query.module_deps(":app", transitive=True)
        self.assertEqual([":feature:chat", ":core:model"], [item["module"] for item in deps["items"]])
        reverse = self.query.module_deps(":core:model", reverse=True, transitive=False)
        self.assertEqual([":feature:chat"], [item["module"] for item in reverse["items"]])

    def test_owners_change_impact_and_violations(self):
        owners = self.query.source_set_owners("android-compose/core/model/src/commonMain/kotlin/Model.kt")
        self.assertEqual(":core:model", owners["items"][0]["module"])
        impact = self.query.change_impact(["android-compose/core/model/src/commonMain/kotlin/Model.kt"])
        self.assertEqual([":app", ":core:model", ":feature:chat"], impact["modules"])
        violations = self.query.architecture_violations()
        self.assertEqual("core-no-app", violations["items"][0]["rule"])

    def test_limits_and_paths_are_validated(self):
        with self.assertRaisesRegex(ValueError, "between 1 and 200"):
            self.query.module_deps(":app", limit=201)
        with self.assertRaisesRegex(ValueError, "repository-relative"):
            self.query.source_set_owners("../secrets")
        with self.assertRaisesRegex(ValueError, "absolute path"):
            architecture_query.import_contract("relative", str(self.db))

    def test_lookup_is_a_bounded_symbol_seam(self):
        result = self.query.lookup("kotlin-library", kind="symbol", limit=1)
        self.assertEqual("architecture_contract", result["backend"])
        self.assertEqual(1, len(result["items"]))
        self.assertTrue(result["truncated"])

    def test_schema_version_mismatch_is_rejected_and_connection_is_closed(self):
        self.query.close()
        db = sqlite3.connect(self.db)
        db.execute("UPDATE metadata SET value='999' WHERE key='schema_version'")
        db.commit()
        db.close()
        real_connect = architecture_query.sqlite3.connect
        opened = []

        def tracked_connect(*args, **kwargs):
            connection = real_connect(*args, **kwargs)
            opened.append(connection)
            return connection

        architecture_query.sqlite3.connect = tracked_connect
        try:
            with self.assertRaisesRegex(ValueError, "unsupported database schema"):
                architecture_query.ArchitectureQuery(str(self.db))
        finally:
            architecture_query.sqlite3.connect = real_connect
        with self.assertRaisesRegex(sqlite3.ProgrammingError, "closed database"):
            opened[0].execute("SELECT 1")
        self.query = architecture_query.ArchitectureQuery.__new__(architecture_query.ArchitectureQuery)
        self.query.close = lambda: None

    def test_database_uri_encodes_reserved_path_characters(self):
        self.query.close()
        encoded_dir = Path(self.temp.name) / "architecture?#"
        encoded_dir.mkdir()
        encoded_db = encoded_dir / "contract db.sqlite"
        architecture_query.import_contract(str(FIXTURE), str(encoded_db))
        self.query = architecture_query.ArchitectureQuery(str(encoded_db))
        self.assertEqual(":app", self.query.get_module(":app")["module"])

    def test_call_tool_reports_missing_required_argument(self):
        with self.assertRaisesRegex(ValueError, "missing required tool argument.*module"):
            architecture_query.call_tool(self.query, "get_module", {})
        with self.assertRaisesRegex(ValueError, "tool arguments must be a JSON object"):
            architecture_query.call_tool(self.query, "get_module", [])

    def test_output_database_symlink_is_rejected(self):
        target = Path(self.temp.name) / "target.db"
        target.touch()
        link = Path(self.temp.name) / "link.db"
        link.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "regular file"):
            architecture_query.validate_db_path(str(link), must_exist=False)

    def test_mcp_lists_and_calls_tools(self):
        requests = "\n".join([
            json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
            json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
                "name": "get_module", "arguments": {"module": ":app"}}}),
        ]) + "\n"
        completed = subprocess.run(
            [sys.executable, str(SCRIPT), "serve", "--db", str(self.db)],
            input=requests, text=True, capture_output=True, check=True,
        )
        responses = [json.loads(line) for line in completed.stdout.splitlines()]
        self.assertEqual(sorted(architecture_query.TOOLS), sorted(tool["name"] for tool in responses[0]["result"]["tools"]))
        self.assertEqual(":app", responses[1]["result"]["structuredContent"]["module"])


if __name__ == "__main__":
    unittest.main()
