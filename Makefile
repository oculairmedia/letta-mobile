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

.PHONY: help verify-build verify-unit-tests verify-device-ready verify-sync verify-stream verify-all verify-release check-cli check-device

help:
	@echo "letta-mobile make targets"
	@echo ""
	@echo "  verify-build         Compile the Android debug build."
	@echo "  verify-unit-tests    Run debug unit tests."
	@echo "  verify-device-ready  Bootstrap a fresh device to an authenticated conversation."
	@echo "                      Required: DEVICE=<serial> APK=<path> BASE_URL=<url> API_KEY=<token> AGENT=<id> CONV=<id>"
	@echo "  verify-sync         Run sync-drift against a live device. Asserts every sample HEALTHY."
	@echo "                      Required: AGENT=<id> CONV=<id>"
	@echo "                      Optional: DEVICE=<serial> ITERATIONS=$(ITERATIONS) INTERVAL=$(INTERVAL) LETTA_URL=$(LETTA_URL)"
	@echo "  verify-stream       Smoke-test the resume-stream endpoint. Asserts at least one event arrives"
	@echo "                      within STREAM_TIMEOUT=$(STREAM_TIMEOUT) seconds for CONV."
	@echo "                      Required: CONV=<id>"
	@echo "                      Optional: STREAM_SEND_TEXT=<text> LETTA_TOKEN=<token>"
	@echo "  verify-all          Run verify-sync + verify-stream in sequence."
	@echo "  verify-release      Run release gates in prereq order and emit a report."
	@echo "                      Optional: VERIFY_RELEASE_ARGS=--json"

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

verify-all: verify-sync verify-stream
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
