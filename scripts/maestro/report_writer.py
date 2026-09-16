#!/usr/bin/env python3
"""Write structured Maestro run JSON and JUnit reports from a shell-safe TSV."""

import csv
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def read_tsv(path):
    with open(path, newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def main():
    if len(sys.argv) != 8:
        raise SystemExit("usage: report_writer.py RUN_DIR RUN_ID OVERALL PROVENANCE_TSV FLOWS_TSV MANIFEST RESULTS")
    run_dir, run_id, overall, provenance_tsv, flows_tsv, manifest_path, results_path = sys.argv[1:]
    provenance = dict((row["key"], row["value"]) for row in read_tsv(provenance_tsv))
    flows = read_tsv(flows_tsv)
    if not flows:
        raise SystemExit("cannot report a run with zero flows")
    for flow in flows:
        flow["artifacts"] = {
            "maestro_stdout": flow.pop("maestro_stdout"),
            "maestro_stderr": flow.pop("maestro_stderr"),
            "gfxinfo": flow.pop("gfxinfo"),
            "logcat": flow.pop("logcat"),
            "hierarchy": flow.pop("hierarchy"),
            "screenshots": flow.pop("screenshots"),
        }
        flow["status"] = flow["status"].upper()
        if flow["status"] not in {"PASS", "FAIL"}:
            raise SystemExit(f"invalid flow status: {flow['status']}")

    derived_overall = "PASS" if all(flow["status"] == "PASS" for flow in flows) else "FAIL"
    if overall != derived_overall:
        raise SystemExit(f"overall status {overall} contradicts flow statuses ({derived_overall})")

    manifest = {
        "schema_version": 1,
        "run_id": run_id,
        "overall_status": overall,
        "report_dir": run_dir,
        "evidence_sensitivity": "local_only_may_contain_user_content",
        "provenance": provenance,
        "flows": [
            {"name": flow["name"], "sha256": flow["sha256"]}
            for flow in flows
        ],
    }
    results = {
        "schema_version": 1,
        "run_id": run_id,
        "overall_status": overall,
        "flows": flows,
    }
    Path(manifest_path).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    Path(results_path).write_text(json.dumps(results, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    suite = ET.Element("testsuite", name="maestro-smokes", tests=str(len(flows)), failures=str(sum(f["status"] != "PASS" for f in flows)))
    for flow in flows:
        case = ET.SubElement(suite, "testcase", name=flow["name"], classname="maestro.smoke")
        if flow["status"] != "PASS":
            ET.SubElement(case, "failure", message=flow["reason"] or flow["status"]).text = flow["reason"]
        ET.SubElement(case, "system-out").text = json.dumps(flow["artifacts"], sort_keys=True)
    ET.ElementTree(suite).write(os.path.join(run_dir, "junit.xml"), encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
