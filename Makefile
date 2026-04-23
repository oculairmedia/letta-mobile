SHELL := /bin/bash

DEVICE   ?=
APK      ?=
BASE_URL ?=
API_KEY  ?=
AGENT    ?=
CONV     ?=

.PHONY: help verify-device-ready

help:
	@echo "letta-mobile make targets"
	@echo ""
	@echo "  verify-device-ready  Bootstrap a fresh device to an authenticated conversation."
	@echo "                       Required: DEVICE=<serial> APK=<path> BASE_URL=<url> API_KEY=<token> AGENT=<id> CONV=<id>"

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
