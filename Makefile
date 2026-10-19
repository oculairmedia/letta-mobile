# letta-mobile local verification and deployment helpers
# Current gates: .github/workflows/android.yml; historical probes: docs/RELEASE.md.

SHELL := /bin/bash

DEVICE              ?=
APK                 ?=
BASE_URL            ?= $(LETTA_URL)
LETTA_URL           ?= http://192.168.50.90:8289
API_KEY             ?= $(LETTA_TOKEN)
LETTA_TOKEN         ?=
AGENT               ?=
CONV                ?=
GRADLEW             := ./gradlew
ANDROID_DIR         := android-compose
SEED_ADDRESS_BOOK_SCRIPT := scripts/deploy/seed-agent-address-book.py
SEED_ADDRESS_BOOK_MANIFEST := scripts/deploy/agent-address-book.manifest.json
SEED_ADDRESS_BOOK_DRY_RUN ?= 1

.PHONY: help lint-telemetry verify-build verify-unit-tests verify-device-ready verify-pm-cron-deploy check-device seed-iroh-address-book

help:
	@echo "letta-mobile make targets"
	@echo ""
	@echo "  lint-telemetry      Fail on known Telemetry convention drift (ERROR-shape events,"
	@echo "                      hand-rolled errorClass/errorMessage, undocumented literal tags)."
	@echo "  verify-build        Compile the Android debug build."
	@echo "  verify-unit-tests   Run debug unit tests."
	@echo "  verify-device-ready Bootstrap a fresh device to an authenticated conversation."
	@echo "                      Required: DEVICE=<serial> APK=<path> BASE_URL=<url> API_KEY=<token> AGENT=<id> CONV=<id>"
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
	@echo "=== verify-build :app:compileRootDebugKotlin ==="
	@cd $(ANDROID_DIR) && $(GRADLEW) :app:compileRootDebugKotlin

verify-unit-tests:
	@echo "=== verify-unit-tests :app:testRootDebugUnitTest ==="
	@cd $(ANDROID_DIR) && $(GRADLEW) :app:testRootDebugUnitTest

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
