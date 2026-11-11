package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreProvider

/**
 * One conformance run against one provider from the fixture, in contract order: descriptor,
 * locking, readiness, input refusals, data checks, and fail-closed last because it takes the
 * backend down. A provider that cannot be created, described or unlocked ends the run early with
 * the findings so far.
 */
internal class KeystoreConformanceRun(private val fixture: KeystoreConformanceFixture) {
    private val recorder = KitRecorder(fixture)

    suspend fun execute(): KeystoreConformanceReport {
        open()?.let { subject -> exercise(subject) }
        return recorder.report()
    }

    private suspend fun open(): KitSubject? {
        val provider = recorder.attempt(Origin("creating a provider")) { fixture.newProvider() } ?: return null
        val descriptor = recorder.attempt(Origin("describe")) { provider.describe() } ?: return null
        return KitSubject(provider, descriptor, recorder, fixture.workspace)
    }

    private suspend fun exercise(subject: KitSubject) {
        val states = KitStateChecks(subject, fixture)
        states.checkDescriptor()
        if (states.checkLocking()) {
            states.checkReady()
            KitInputChecks(subject).run()
            KitDataChecks(subject).run()
            states.checkFailClosed()
        }
        captureIdentity(subject.provider)
    }

    private fun captureIdentity(provider: KeystoreProvider) {
        recorder.capture(Origin("the provider's toString"), provider.toString())
    }
}
