# Linux Nucleus hotkey JNI

Nucleus 2.1.5 `x11_grab` discards the process's previous XErrorHandler,
leaving Xlib's fatal default installed. PR #2233's packaged GDB trace proves
this makes a later AWT SetInputFocus BadMatch terminate the app.

`pins.json` identifies the exact upstream commit, Maven JAR and SHA-256 of
each downloaded source input (including upstream build definitions and MIT
license). `preserve-xerror-handler.patch` changes only the two handler calls.
The Gradle Linux-only dependency builds a derived JAR under `desktop/build`,
replacing just `nucleus/native/linux-x64/libnucleus_global_hotkey.so`.
Every other member is checked byte-for-byte; Windows uses its original Maven
dependency and does not register this task. No Maven cache, user native cache
or system library is changed. Nucleus's existing CRC/size resource fingerprint
selects the rebuilt JNI without application or loader changes.

CI needs gcc, pkg-config, libx11-dev, libglib2.0-dev, Xvfb and JNI headers.
The compiler/linker options mirror the verified upstream `build.sh`; we do
not execute its user-cache deletion step. Before producing the JAR, the helper
requires the regression to fail on unpatched source with lost-handler exit 42,
then pass three successful/conflicting grab and unregister/retry cycles on
patched source. Each phase sends a real BadWindow to the sentinel host handler
to prove restoration and error delivery. Native compilation and tests run in
CI, not on the shared development host.

The JAR carries the MIT notice and source/patch/native digests under
`META-INF/letta/`. The packaged Linux inspector verifies those against this
pin and patch and rejects duplicate hotkey natives. The original packaged
visible-window smoke, startup-error checks and failure-only GDB replay remain
required. A source regression alone does not establish packaged acceptance.

## Synchronization boundary

This corrects handler loss after a completed grab; it does not establish
process-wide Xlib trap safety. Upstream unlocks `g_mutex` before `x11_grab`,
and `x11err` and XSetErrorHandler are global. Concurrent registrations could
interleave traps and restore the wrong handler; a library-local mutex alone
also cannot serialize unrelated AWT/GTK handler changes. A concurrency fix
would need to serialize actual JNI register/unregister/shutdown operations,
protect the full trap interval (including error drain and restoration), and
coordinate or chain unrelated display/request errors with the host's Xlib
handler ownership. That is separate from this two-line correction; no blanket
error suppression or speculative framework locking is added here.
