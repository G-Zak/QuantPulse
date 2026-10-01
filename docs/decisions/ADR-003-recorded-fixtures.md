# ADR-003 — Record upstream responses once; never call the live API in dev or test

- **Status:** Accepted
- **Date:** 2026-08-03

## Context

Drahmi's free tier allows **100 requests per day**, per key, resetting at 00:00 UTC.
A single `mvn verify` with a handful of integration tests would exhaust a meaningful
fraction of that. Running the application locally for an afternoon would exhaust all of
it — and then development stops until tomorrow.

The data is also delayed (~15 min for quotes, T+1D for history), so live calls buy no
freshness during development anyway.

Separately, the published documentation is not an accurate description of the API. It
gives sector codes as `BANK`/`REAL`; the API returns 25 French codes including
`BANQU` and `TÉLÉC`. It shows `beta: 0.85`; the API returns `null`. It documents
`POST /intelligence/portfolio/summary` as 200; it answers 201. A domain model built from
the docs would have been wrong in at least four places.

## Decision

Record every endpoint's real response **once** into `ops/fixtures/` (36 files, recorded
2026-08-03). Those files are the contract. Thereafter:

- **tests** serve fixtures through WireMock;
- the **`dev` profile** replays fixtures, with a GBM simulator (ported from FinTrack's
  `mock-market-api`) for intraday movement;
- only the **`live` profile** calls the real API;
- `ops/tools/record_fixtures.py` is the sole component permitted to call it, and it
  **skips fixtures that already exist**, so re-running costs zero requests.

The recorder also enforces a `--floor` on `X-RateLimit-Remaining` and aborts rather than
exhausting the budget, and supports `--dry-run` to price a change before making it.

## Rationale

Beyond conserving quota, this buys the properties you want from tests anyway:
deterministic (the same bytes every run), fast (no network), and able to reproduce
conditions the live API won't produce on demand — a 429, a malformed payload, a `null`
`beta`, a zero-volume trading day.

Recording before modelling is what surfaced all eleven doc/reality discrepancies now
catalogued in `docs/api/drahmi-reference.md`.

## Consequences

**Fixtures go stale.** They are a snapshot of 2026-08-03. If the upstream changes shape,
tests still pass while production breaks — the standard weakness of recorded mocks. The
honest mitigation is a small, separately-tagged contract test run against the live API
on demand (not in CI), which re-records and diffs. Not yet built; named here so it can
be discussed rather than discovered.

**Fixtures are committed.** They contain only public market data, no secrets. This is
what makes the repo clonable and the build reproducible offline.

**Quota accounting.** Recording all 36 cost 36 calls (plus 5 during exploration), leaving
61 of that day's 100. Zero has been spent on development since.

## Alternatives rejected

- **Hand-written mocks.** Free, but they encode what you *believe* the API returns —
  which, per the eleven discrepancies above, was wrong.
- **Live calls in tests.** Non-deterministic, network-dependent, and would burn the
  entire daily quota in a few `mvn verify` runs.
- **A paid tier (1000/day).** Removes the constraint, and with it the most interesting
  design problem in the project.
