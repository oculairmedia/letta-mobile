# Sentry ANR / crash budget dashboard

This document defines the observability artifacts for `letta-mobile-o7ob.3.5`.

The repo already stores dashboard and alert definitions as committed artifacts
under `docs/observability/`. This bead follows the same pattern, but uses
**Sentry-native payloads** where the Sentry API is the source of truth and
documents the remaining gaps where Sentry does not expose a first-class mobile
budget primitive.

## Artifact layout

- Dashboard payload: `docs/observability/sentry-anr-crash-budget-dashboard.json`
- Alert payloads: `docs/observability/sentry-anr-crash-budget-alerts.json`

These files are intended to be:

1. version-controlled,
2. human-reviewed alongside the app telemetry that feeds them, and
3. used as the reprovisioning source when rebuilding the Sentry dashboard.

## What the dashboard covers

The dashboard groups the three budget checks called for in the bead plus the
existing Lane C1 transaction set:

1. **Crash-free sessions (7d)** — the primary crash budget tile.
2. **Cold startup p95 (7d)** — tracks the startup budget on the `app.startup`
   transaction tagged `startupType:cold`.
3. **Warm startup p95 (7d)** — informational comparison tile.
4. **App hangs / ANR issue count (7d)** — fallback ANR visibility tile.
5. **Lane C1 transactions** — `app.startup`, `chat.sync_cycle`,
   `chat.send_message`, `bot.turn`, and `composer.keystroke_to_render`.

The startup tiles intentionally reuse the transaction taxonomy from
`docs/performance/sentry-taxonomy.md` instead of inventing a second set of
performance names.

## Alert rules

### Exact Sentry-native rules

The following rules map cleanly onto Sentry's alert API and are encoded as
request payloads in `sentry-anr-crash-budget-alerts.json`:

- **Crash-free sessions < 99.5%** over a 24h window, with a 7d comparison
  delta for historical context.
- **Cold startup p95 regression > 15% vs previous 7d** using the
  `app.startup` transaction filtered to `startupType:cold`.

### ANR / app-hang caveat

`Google Play vitals` ANR rate (`> 0.47% weekly`) is the desired product
threshold, but Sentry's public alert API does not expose an equally direct
mobile ANR-rate aggregate the way it exposes crash-free sessions.

For that reason, this bead ships an **issue-alert fallback** that watches for a
weekly spike in Sentry app-hang / ANR-like issues instead of pretending Sentry
can natively calculate the exact Play-vitals percentage.

Treat that issue alert as:

- a **triage accelerator**, and
- the dashboard's app-hang tile as the **manual review surface** for the
  Play-vitals threshold.

If Sentry later exposes a first-class ANR-rate metric for Android mobile apps,
replace the fallback payload with a real metric alert in the same JSON file.

### Known limitations

This v1 ships a documented app-hang / issue-volume fallback instead of an exact
Play-vitals-style ANR-rate alert because Sentry's public alert API does not yet
surface an equivalent first-class mobile ANR-rate aggregate. `letta-mobile-j25x`
tracks the follow-up upgrade path from this fallback to a dimensionally correct
ANR-rate metric once the required data source or custom metric exists.

## Provisioning workflow

### Dashboard

Sentry's dashboard API supports CRUD, but the repo does not currently carry a
Terraform or other infrastructure layer for Sentry. The practical workflow is:

1. create or update the dashboard in Sentry using the committed JSON as the
   source payload,
2. retrieve the resulting dashboard JSON from Sentry,
3. normalize any environment-specific IDs if needed, and
4. commit the updated JSON back to the repo.

### Alerts

Apply the payloads in `sentry-anr-crash-budget-alerts.json` against the Sentry
API endpoints documented in that file. The JSON is structured so it can be
copied into a small script or translated into Terraform/Pulumi later without
rewriting the alert semantics.

## Validation checklist

Before marking the bead done in a fresh environment:

1. Confirm the dashboard imports cleanly and all widgets resolve against the
   `letta-mobile` project.
2. Confirm the crash-free sessions metric alert can be created without payload
   changes.
3. Confirm the cold-start p95 alert query resolves against the
   `app.startup` transaction and `startupType:cold` tag.
4. Confirm the ANR fallback issue alert resolves against the project's mobile
   issue data model, or document the exact field adjustment required.
5. Update `RELEASE.md` references if the final dashboard title or import path
   changes during provisioning.

## Related

- `letta-mobile-o7ob.3.1` — Sentry transaction taxonomy
- `docs/performance/sentry-taxonomy.md`
- `docs/observability/grafana-crash-sentry.json`
- `docs/observability/grafana-crash-sentry-alerts.yaml`
- `docs/RELEASE.md`
