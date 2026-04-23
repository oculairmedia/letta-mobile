# letta-mobile — release verification targets
#
# These targets wrap the `letta-cli` diagnostic commands into pass/fail gates
# suitable for a release checklist (see docs/RELEASE.md). They are deliberately
# self-contained — no external CI infra required. Run from a workstation with
# a device attached (verify-sync) or from anywhere with network access to the
# Letta server (verify-stream).
#
# Revived from orphaned work in backup/local-main-pre-origin-reset-2026-04-22.
# Adaptation for PR A: this restoration intentionally excludes the
# `lint-telemetry` target and does not wire it into `verify-all`, because
# `scripts/lint_telemetry.py` is restored separately in 1s7p PR B.

SHELL := /bin/bash

# --- Configuration (override on the command line, e.g. `make verify-sync AGENT=agent-xxx CONV=conv-yyy`) ---
LETTA_URL       ?= http://192.168.50.90:8289
AGENT           ?=
CONV            ?=
ITERATIONS      ?= 6
INTERVAL        ?= 10
STREAM_TIMEOUT  ?= 60
CLI             := cli/letta-cli

# --- Meta ---
.PHONY: help verify-sync verify-stream verify-all check-cli check-device

help:
	@echo "letta-mobile make targets"
	@echo ""
	@echo "  verify-sync    Run sync-drift against a live device. Asserts every sample HEALTHY."
	@echo "                 Required:  AGENT=<id> CONV=<id>"
	@echo "                 Optional:  ITERATIONS=$(ITERATIONS) INTERVAL=$(INTERVAL) LETTA_URL=$(LETTA_URL)"
	@echo ""
	@echo "  verify-stream  Smoke-test the resume-stream endpoint. Asserts at least one event arrives"
	@echo "                 within STREAM_TIMEOUT=$(STREAM_TIMEOUT) seconds for CONV. Does not require"
	@echo "                 a device; independently validates server-side realtime delivery."
	@echo "                 Required:  CONV=<id>"
	@echo ""
	@echo "  verify-all     Run verify-sync + verify-stream in sequence. Release-gate entry point."
	@echo ""
	@echo "  help           Show this message."

# --- Preconditions ---

check-cli:
	@if [[ ! -x $(CLI) ]]; then \
		echo "ERROR: $(CLI) not found or not executable"; \
		exit 2; \
	fi

check-device:
	@if ! command -v adb >/dev/null 2>&1; then \
		echo "ERROR: adb not on PATH — verify-sync needs a connected device"; \
		exit 2; \
	fi
	@DEVICE_COUNT=$$(adb devices | awk 'NR>1 && $$2=="device"' | wc -l); \
	if [[ $$DEVICE_COUNT -eq 0 ]]; then \
		echo "ERROR: no authorized device attached (adb devices shows 0)"; \
		exit 2; \
	fi

# --- verify-sync ---

verify-sync: check-cli check-device
	@if [[ -z "$(AGENT)" || -z "$(CONV)" ]]; then \
		echo "ERROR: verify-sync requires AGENT=<id> and CONV=<id>"; \
		exit 2; \
	fi
	@echo "=== verify-sync AGENT=$(AGENT) CONV=$(CONV) iterations=$(ITERATIONS) interval=$(INTERVAL)s ==="
	@OUTPUT=$$(LETTA_URL=$(LETTA_URL) $(CLI) sync-drift \
		--agent $(AGENT) --conversation $(CONV) \
		--watch --iterations $(ITERATIONS) --interval $(INTERVAL)); \
	echo "$$OUTPUT"; \
	CLEAN=$$(echo "$$OUTPUT" | sed -r 's/\x1B\[[0-9;]*[mK]//g'); \
	TOTAL=$$(echo "$$CLEAN" | grep -cE 'drift=[0-9]+s.*(HEALTHY|STALE|BROKEN|UNKNOWN)' || true); \
	HEALTHY=$$(echo "$$CLEAN" | grep -cE 'drift=[0-9]+s.*HEALTHY$$' || true); \
	if [[ $$TOTAL -lt $(ITERATIONS) ]]; then \
		echo ""; \
		echo "FAIL verify-sync: only $$TOTAL/$$(echo $(ITERATIONS)) samples produced a verdict"; \
		exit 1; \
	fi; \
	if [[ $$HEALTHY -ne $$TOTAL ]]; then \
		echo ""; \
		echo "FAIL verify-sync: $$HEALTHY/$$TOTAL samples HEALTHY (others were STALE/BROKEN/UNKNOWN)"; \
		exit 1; \
	fi; \
	echo ""; \
	echo "PASS verify-sync: $$HEALTHY/$$TOTAL samples HEALTHY"

# --- verify-stream ---

verify-stream: check-cli
	@if [[ -z "$(CONV)" ]]; then \
		echo "ERROR: verify-stream requires CONV=<id>"; \
		exit 2; \
	fi
	@echo "=== verify-stream CONV=$(CONV) timeout=$(STREAM_TIMEOUT)s ==="
	@echo "Listening for up to $(STREAM_TIMEOUT)s. Trigger a run in the conversation now."
	@OUTPUT=$$(LETTA_URL=$(LETTA_URL) timeout $(STREAM_TIMEOUT) $(CLI) stream-watch \
		--conversation $(CONV) --backoff-start 1 --backoff-max 5 || true); \
	echo "$$OUTPUT"; \
	CLEAN=$$(echo "$$OUTPUT" | sed -r 's/\x1B\[[0-9;]*[mK]//g'); \
	EVENTS=$$(echo "$$CLEAN" | grep -oE 'Events received: [0-9]+' | grep -oE '[0-9]+$$' | tail -1); \
	EVENTS=$${EVENTS:-0}; \
	if [[ $$EVENTS -lt 1 ]]; then \
		echo ""; \
		echo "FAIL verify-stream: 0 events received in $(STREAM_TIMEOUT)s window"; \
		echo "  Either the resume-stream endpoint is broken, or no run fired in the window."; \
		echo "  Re-run and trigger a message send during the window."; \
		exit 1; \
	fi; \
	echo ""; \
	echo "PASS verify-stream: $$EVENTS event(s) received"

# --- verify-all ---

verify-all: verify-sync verify-stream
	@echo ""
	@echo "=== verify-all PASS ==="
