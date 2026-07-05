# STOP — h30cy work handed off to a fresh context (2026-07-05 ~14:15 EDT)

Emmanuel has asked me (Meridian, conv-16c2f589) to HALT all work on the h30cy
Iroh duplicate/character-drop bug in this worktree and hand it off to a fresh
context via a consolidated bead.

**Do NOT continue committing to `ab20f8b2c` / this worktree.** The dual-ingest
registry guard (ab20f8b2c) + replay=1 (6945c9db3) are NECESSARY BUT INSUFFICIENT —
verified on-device 14:10 with BOTH ends byte-identical at ab20f8b2c: it STILL
dupes and drops characters. A second corruption path remains.

All findings, the byte-loss fingerprint, and the next diagnostic step are recorded
in bead **letta-mobile-h30cy** (see the latest comments). Pick up from there in a
fresh context. Please stop and acknowledge.
