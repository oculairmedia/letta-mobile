# Android Compose Agent Notes

## Embedded Runtime Changes Are Device-Gated

Changes under `app/src/main/java/com/letta/mobile/runtime/local/**` REQUIRE a green `scripts/verify-embedded.sh` run on a physical Android device before merge.

This is device-executed, not emulator/CI-executed: embedded runtime coverage depends on a real device and ADB. Treat it as a required pre-merge ritual with no bypass. It is the embedded-runtime layer of the capability-regression contract (`lcp-ubff`), and it exists to prevent regressions in launch/R8 JNI survival, `.litertlm` gating, local-agent session switching, and embedded Node → provider → assistant turns.
