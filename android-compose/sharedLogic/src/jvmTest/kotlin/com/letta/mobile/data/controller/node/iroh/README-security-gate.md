# Iroh authentication security gate (d6e8g.8)

`IrohSecurityGateTest` is the consolidated, hermetic, adversarial regression
suite for the Iroh auth/authz layer. It is **required for merge**: it lives in
`:sharedLogic:jvmTest`, which the `shared-multiplatform` CI job runs via
`./gradlew :sharedLogic:allTests` (see `.github/workflows/android.yml`), and
`shared-multiplatform` is a branch-protection required check.

## Coverage (threat matrix)

| Threat | Covered by |
|---|---|
| Anonymous connection | `anonymousStartupIsRefusedUnlessExplicitlyOptedIn` |
| Missing / wrong / unapproved-peer token | `missingWrongAndUnapprovedPeerTokensAllFailClosed` |
| Brute-force throttling then close | `bruteForceIsThrottledThenClosedEvenForTheValidToken` |
| Expired / replayed invitation, revoked peer | `expiredReplayedAndRevokedPairingsFailClosed` |
| Unauthorized admin RPC, capability denial | `unauthorizedAdminRpcAndCrossCapabilityAccessDeny` |
| Read-only peer runtime/mutation attempt | `readOnlyPeerCannotDriveRuntimeOrMutate` |
| Approved peer + credential + capability (positive) | `approvedPeerWithCredentialAndCapabilityIsAdmitted` |
| Secret redaction (canary scan) | `authDenialTelemetryNeverLeaksInjectedCanarySecrets` |

Companion component suites in the same package extend the matrix:
`IrohAuthPolicyTest`, `IrohBearerAuthVerifierTest`, `IrohPairingServiceTest`,
`IrohPeerCapabilitiesTest`.

## Invariants asserted

- **Zero side effects on denial.** Every denial path is checked against a
  `SideEffectSpy`; the test fails if a denied decision would have started a
  runtime, dispatched admin RPC, or applied a mutation.
- **No secret leakage.** Injected canary token/invite strings must never appear
  in captured telemetry.
- **The gate is not vacuous.** `gateFailsIfAuthorizationIsBypassed` proves a
  bypassed (admin.full) decision flips the outcome, so the suite cannot pass
  while authorization is disabled.

## Extending

When adding an auth/authz decision point, add both its component test and a row
here that asserts the fail-closed outcome AND `SideEffectSpy.assertUntouched`.
