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

DEVICE              ?=
APK                 ?=
BASE_URL            ?= $(LETTA_URL)
LETTA_URL           ?= http://192.168.50.90:8289
API_KEY             ?= $(LETTA_TOKEN)
LETTA_TOKEN         ?=
AGENT               ?=
CONV                ?=
ITERATIONS          ?= 6
INTERVAL            ?= 10
STREAM_TIMEOUT      ?= 60
STREAM_SEND_TEXT    ?=
VERIFY_RELEASE_ARGS ?=
CLI                 := cli/letta-cli
GRADLEW             := ./gradlew
ANDROID_DIR         := android-compose
VERIFY_RELEASE_SCRIPT := scripts/release/verify-release.sh
SEED_ADDRESS_BOOK_SCRIPT := scripts/deploy/seed-agent-address-book.py
SEED_ADDRESS_BOOK_MANIFEST := scripts/deploy/agent-address-book.manifest.json
SEED_ADDRESS_BOOK_DRY_RUN ?= 1

.PHONY: help lint-telemetry verify-build verify-unit-tests verify-device-ready verify-sync verify-stream verify-all verify-release verify-pm-cron-deploy check-cli check-device seed-iroh-address-book

help:
	@echo "letta-mobile make targets"
	@echo ""
	@echo "  lint-telemetry      Fail on known Telemetry convention drift (ERROR-shape events,"
	@echo "                      hand-rolled errorClass/errorMessage, undocumented literal tags)."
	@echo "  verify-build        Compile the Android debug build."
	@echo "  verify-unit-tests   Run debug unit tests."
	@echo "  verify-device-ready Bootstrap a fresh device to an authenticated conversation."
	@echo "                      Required: DEVICE=<serial> APK=<path> BASE_URL=<url> API_KEY=<token> AGENT=<id> CONV=<id>"
	@echo "  verify-sync         Run sync-drift against a live device. Asserts every sample HEALTHY."
	@echo "                      Required: AGENT=<id> CONV=<id>"
	@echo "                      Optional: DEVICE=<serial> ITERATIONS=$(ITERATIONS) INTERVAL=$(INTERVAL) LETTA_URL=$(LETTA_URL)"
	@echo "  verify-stream       Smoke-test the resume-stream endpoint. Asserts at least one event arrives"
	@echo "                      within STREAM_TIMEOUT=$(STREAM_TIMEOUT) seconds for CONV."
	@echo "                      Required: CONV=<id>"
	@echo "                      Optional: STREAM_SEND_TEXT=<text> LETTA_TOKEN=<token>"
	@echo "  verify-all          Run lint-telemetry + verify-sync + verify-stream in sequence."
	@echo "  verify-release      Run release gates in prereq order and emit a report."
	@echo "                      Optional: VERIFY_RELEASE_ARGS=--json"
	@echo "  seed-iroh-address-book  Populate ~/.letta/iroh/ (identities dir + kv + seed-done"
	@echo "                          marker) from matrix_letta.identities + the checked-in"
	@echo "                          manifest. Default is --dry-run for release checklists."
	@echo "                          Set SEED_ADDRESS_BOOK_DRY_RUN=0 to perform a real seed."
	@echo "                          Set FORCE=1 to bypass the .seedDone idempotency guard."
	@echo "  verify-pm-cron-deploy Three-assertion gate for the pm-30m heartbeat cron (letta-mobile-g87by):"
	@echo "                      (a) install-pm-cron.sh --check exits 0, (b) cron-sensing-check.sh"
	@echo "                      exits 0, (c) jq -e '.tasks | length >= 1' returns true. Exits non-zero"
	@echo "                      on any failure. Read-only against the live ledger -- never mutates."
	@echo "                      Optional: LETTA_HOME, LETTA_CRONS_JSON, LETTA_LOCAL_BACKEND_DIR,"
	@echo "                      LETTA_AGENT_ID overrides for sandboxed runs."

lint-telemetry:
	@python3 scripts/lint_telemetry.py

check-cli:
	@if [[ ! -x $(CLI) ]]; then \
		echo "ERROR: $(CLI) not found or not executable"; \
		exit 2; \
	fi

check-device:
	@if ! command -v adb >/dev/null 2>&1; then \
		echo "ERROR: adb not on PATH — device gates need a connected device"; \
		exit 2; \
	fi
	@DEVICE_COUNT=$$(adb devices | awk 'NR>1 && $$2=="device"' | wc -l); \
	if [[ $$DEVICE_COUNT -eq 0 ]]; then \
		echo "ERROR: no authorized device attached (adb devices shows 0)"; \
		exit 2; \
	fi

verify-build:
	@echo "=== verify-build :app:compileDebugKotlin ==="
	@cd $(ANDROID_DIR) && $(GRADLEW) :app:compileDebugKotlin

verify-unit-tests:
	@echo "=== verify-unit-tests :app:testDebugUnitTest ==="
	@cd $(ANDROID_DIR) && $(GRADLEW) :app:testDebugUnitTest

verify-device-ready:
	@if [[ -z "$(DEVICE)" || -z "$(APK)" || -z "$(BASE_URL)" || -z "$(API_KEY)" || -z "$(AGENT)" || -z "$(CONV)" ]]; then \
		echo "ERROR: verify-device-ready requires DEVICE, APK, BASE_URL, API_KEY, AGENT, and CONV"; \
		exit 2; \
	fi
	@./scripts/release/bootstrap-device.sh \
		--device "$(DEVICE)" \
		--apk "$(APK)" \
		--base-url "$(BASE_URL)" \
		--api-key "$(API_KEY)" \
		--agent "$(AGENT)" \
		--conv "$(CONV)"

verify-sync: check-cli check-device
	@if [[ -z "$(AGENT)" || -z "$(CONV)" ]]; then \
		echo "ERROR: verify-sync requires AGENT=<id> and CONV=<id>"; \
		exit 2; \
	fi
	@echo "=== verify-sync AGENT=$(AGENT) CONV=$(CONV) iterations=$(ITERATIONS) interval=$(INTERVAL)s ==="; \
	OUTPUT=$$(LETTA_URL="$(LETTA_URL)" $(CLI) sync-drift \
		--agent "$(AGENT)" --conversation "$(CONV)" \
		--watch --iterations "$(ITERATIONS)" --interval "$(INTERVAL)" 2>&1); \
	echo "$$OUTPUT"; \
	CLEAN=$$(echo "$$OUTPUT" | sed -r 's/\x1B\[[0-9;]*[mK]//g'); \
	TOTAL=$$(echo "$$CLEAN" | grep -cE 'drift=-?[0-9]+s.*(HEALTHY|STALE|BROKEN|UNKNOWN)' || true); \
	HEALTHY=$$(echo "$$CLEAN" | grep -cE 'drift=-?[0-9]+s.*HEALTHY$$' || true); \
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

verify-stream: check-cli
	@if [[ -z "$(CONV)" ]]; then \
		echo "ERROR: verify-stream requires CONV=<id>"; \
		exit 2; \
	fi
	@echo "=== verify-stream CONV=$(CONV) timeout=$(STREAM_TIMEOUT)s ==="
	@TMP_OUTPUT=$$(mktemp); \
	trap 'rm -f "$$TMP_OUTPUT"' EXIT; \
	if [[ -n "$(STREAM_SEND_TEXT)" ]]; then \
		if [[ -z "$(LETTA_TOKEN)" ]]; then \
			echo "ERROR: STREAM_SEND_TEXT requires LETTA_TOKEN/API_KEY so the CLI can trigger a send"; \
			exit 2; \
		fi; \
		echo "Listening for up to $(STREAM_TIMEOUT)s and auto-sending a trigger message."; \
		(LETTA_URL="$(LETTA_URL)" LETTA_TOKEN="$(LETTA_TOKEN)" timeout "$(STREAM_TIMEOUT)" $(CLI) stream-watch \
			--conversation "$(CONV)" --backoff-start 1 --backoff-max 5 || true) >"$$TMP_OUTPUT" 2>&1 & \
		WATCH_PID=$$!; \
		sleep 2; \
		curl -sS -X POST "$(LETTA_URL)/v1/conversations/$(CONV)/messages" \
			-H "Content-Type: application/json" \
			-H "Authorization: Bearer $(LETTA_TOKEN)" \
			-d '{"input":"$(STREAM_SEND_TEXT)","streaming":true,"background":true}' >/dev/null; \
		wait $$WATCH_PID || true; \
	else \
		echo "Listening for up to $(STREAM_TIMEOUT)s. Trigger a run in the conversation now."; \
		LETTA_URL="$(LETTA_URL)" LETTA_TOKEN="$(LETTA_TOKEN)" timeout "$(STREAM_TIMEOUT)" $(CLI) stream-watch \
			--conversation "$(CONV)" --backoff-start 1 --backoff-max 5 >"$$TMP_OUTPUT" 2>&1 || true; \
	fi; \
	OUTPUT=$$(cat "$$TMP_OUTPUT"); \
	echo "$$OUTPUT"; \
	CLEAN=$$(echo "$$OUTPUT" | sed -r 's/\x1B\[[0-9;]*[mK]//g'); \
	EVENTS=$$(echo "$$CLEAN" | grep -oE 'Events received: [0-9]+' | grep -oE '[0-9]+$$' | tail -1); \
	EVENTS=$${EVENTS:-0}; \
	if [[ $$EVENTS -lt 1 ]]; then \
		echo ""; \
		echo "FAIL verify-stream: 0 events received in $(STREAM_TIMEOUT)s window"; \
		echo "  Either the resume-stream endpoint is broken, or no run fired in the window."; \
		exit 1; \
	fi; \
	echo ""; \
	echo "PASS verify-stream: $$EVENTS event(s) received"

verify-all: lint-telemetry verify-sync verify-stream
	@echo ""
	@echo "=== verify-all PASS ==="

verify-release:
	@BASE_URL="$(BASE_URL)" \
		LETTA_URL="$(LETTA_URL)" \
		API_KEY="$(API_KEY)" \
		LETTA_TOKEN="$(LETTA_TOKEN)" \
		DEVICE="$(DEVICE)" \
		APK="$(APK)" \
		AGENT="$(AGENT)" \
		CONV="$(CONV)" \
		ITERATIONS="$(ITERATIONS)" \
		INTERVAL="$(INTERVAL)" \
		DEVICE="$(DEVICE)" \
		STREAM_TIMEOUT="$(STREAM_TIMEOUT)" \
		CLI="$(CLI)" \
		ANDROID_DIR="$(ANDROID_DIR)" \
		GRADLEW="$(GRADLEW)" \
		make="$(MAKE)" \
		./$(VERIFY_RELEASE_SCRIPT) $(VERIFY_RELEASE_ARGS)

# letta-mobile-bn008.7 — populate ~/.letta/iroh/ from matrix_letta + manifest.
# Default mode is --dry-run so release checklists don't accidentally write to
# the host's HOME. Pass SEED_ADDRESS_BOOK_DRY_RUN=0 for a real seed; pass
# FORCE=1 to bypass the .seedDone idempotency guard.
#
# Artifacts:
#   ~/.letta/iroh/identities/          (mode 0700; per-agent JSON populated by wrapper on first dial)
#   ~/.letta/iroh/agent-addresses.kv   (mode 0644; FileIrohAgentAddressStore)
#   ~/.letta/iroh/.seedDone            (mode 0600; iroh.addressbook.seedDone marker)
#
# AC #5 (roundtrip proof via meridian agent-message send --probe) is
# deferred until bn008.6 lands the receiver wire — see the bead description
# (deferred-because: bn008.6).
seed-iroh-address-book:
	@if [[ ! -x $(SEED_ADDRESS_BOOK_SCRIPT) ]]; then \
		echo "ERROR: $(SEED_ADDRESS_BOOK_SCRIPT) not found or not executable"; \
		exit 2; \
	fi
	@if [[ ! -f $(SEED_ADDRESS_BOOK_MANIFEST) ]]; then \
		echo "ERROR: $(SEED_ADDRESS_BOOK_MANIFEST) not found"; \
		exit 2; \
	fi
	@ARGS="--from-manifest $(SEED_ADDRESS_BOOK_MANIFEST)"; \
	if [[ "$(SEED_ADDRESS_BOOK_DRY_RUN)" == "1" ]]; then \
		ARGS="$$ARGS --dry-run"; \
		echo "=== seed-iroh-address-book (dry-run) ==="; \
	else \
		echo "=== seed-iroh-address-book (LIVE) ==="; \
	fi; \
	if [[ "$(FORCE)" == "1" ]]; then \
		ARGS="$$ARGS --force"; \
	fi; \
	python3 $(SEED_ADDRESS_BOOK_SCRIPT) $$ARGS

# letta-mobile-g87by — three-assertion gate for the pm-30m heartbeat cron.
# (a) install-pm-cron.sh --check exits 0 (schedule registered, never mutates).
# (b) cron-sensing-check.sh exits 0 (structural + per-task both green).
# (c) jq -e '.tasks | length >= 1' on the live ledger returns true.
# Read-only. CI gate target.
verify-pm-cron-deploy:
	@echo "=== verify-pm-cron-deploy (letta-mobile-g87by) ==="
	@bash scripts/deploy/verify-pm-cron-deploy
