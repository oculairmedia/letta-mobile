#!/usr/bin/env bash
# Local preflight before a push: every no-build gate that has failed PRs, without Gradle or network.
#
# It is quick-check.sh (one PASS/FAIL line per gate, non-zero exit on any failure) under the name
# contributors look for, plus a note for the one structure check that needs Gradle output:
# tools/structure_report/structure_report.py reads android-compose/build/reports/structure, so it is
# skipped here; quick-check's `structure` gate checks the same caps and cycles from source.
#
# Usage: preflight.sh [--base REF] [--verbose]   (arguments go to quick-check.sh)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "SKIP  structure-report   needs Gradle output: run :architecture-tests:structureReport first"
exec bash "$here/quick-check.sh" "$@"
