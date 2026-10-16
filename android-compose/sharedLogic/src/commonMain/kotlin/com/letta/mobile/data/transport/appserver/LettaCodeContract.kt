package com.letta.mobile.data.transport.appserver

/**
 * letta-mobile-ckmub.5: the one letta-code wire contract the clients are built against, and the
 * one runtime that is knowingly below it. User-facing text cites these instead of spelling
 * versions out. `LettaCodeVersionPinsTest` (appserver-cli) fails when they disagree with the
 * contract matrix, the desktop bundle, the CI job or the embedded pin in
 * `app/embedded-runtime/embedded-runtime.properties`. The README next to this file has the full
 * pin table.
 */
object LettaCodeContract {
    /**
     * THE wire contract: the protocol `.d.ts` corpus the Kotlin wire types are verified against,
     * equal to the desktop bundled runtime. Hosts run at least this version; there is no
     * compatibility path for older servers (no users yet, 2026-10-16). Decoding stays
     * additive-tolerant so a newer server's extra fields never break a client.
     */
    const val VERSION: String = "0.34.8"

    /**
     * The letta-code the Android app embeds (nodejs-mobile, Node 18). Developer use only: it
     * cannot reach [VERSION] (letta-code 0.26.2+ needs Node >= 22.19) and a full on-device
     * execution runtime is a Google Play policy risk, so production Android talks to a remote
     * host (desktop or Iroh) that runs [VERSION].
     */
    const val ANDROID_EMBEDDED_VERSION: String = "0.26.1"
}
