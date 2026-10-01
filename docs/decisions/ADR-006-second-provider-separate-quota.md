# ADR-006 — A second upstream gets its own narrow interface and its own quota governor

- **Status:** Accepted
- **Date:** 2026-09-28

## Context

Every price in the system is in MAD, and until now nothing could express an amount in
any other currency: `Money` refuses to add USD to MAD "without an explicit FX rate", and
no rate existed anywhere. Alpha Vantage supplies FX spot rates (and global daily series
for a future benchmark), on a free tier of **25 requests per day**.

Alpha Vantage does **not** cover the Casablanca exchange. It is not an alternative source
for anything Drahmi provides.

Two structural questions followed:

1. Does Alpha Vantage implement `MarketDataProvider`, the interface Drahmi sits behind?
2. Does it share `QuotaGovernor` — generalised to a `(provider, date)` key with two beans —
   or get a governor of its own?

## Decision

1. **A separate, narrow `AlphaVantageClient` interface** with exactly the two calls this
   provider serves (`fetchFxRate`, `fetchDailySeries`), and the same live/fixture
   implementation pair as Drahmi.
2. **A separate `AlphaVantageQuotaGovernor`** with its own table
   (`alphavantage_quota_budget`), its own floors (CRITICAL 0, NORMAL 5), and its own
   Resilience4j instance (`alphavantage`). `QuotaGovernor` and `quota_budget` are unchanged.
   Calls are still audited in the shared `api_call_log`, prefixed `alphavantage:`.

## Rationale

**Why not `MarketDataProvider`.** Eleven of its twelve methods have no Alpha Vantage
equivalent. Implementing them as "unsupported" would make it *legal* to wire Alpha Vantage
in as a Drahmi fallback — which would compile, start, and silently return nothing for all
81 instruments. A narrow interface makes that mistake unexpressible.

**Why not generalise `QuotaGovernor`.** The mechanism is shared; the details are not:

| | Drahmi | Alpha Vantage |
|---|---|---|
| Daily limit | 100 | 25 |
| Floors | CRITICAL 0 · HIGH 5 · NORMAL 15 · BACKFILL 35 | CRITICAL 0 · NORMAL 5 |
| Upstream's view of the budget | `X-RateLimit-Remaining` on every response | nothing — until it refuses |
| Reconciliation | numeric: trust the header when it is lower | binary: a refusal marks the day exhausted |
| Refusal looks like | HTTP 429 | HTTP 200 with a `Note`/`Information` body |

A generalised governor would need per-provider floor tables and two reconciliation
strategies — and getting there means widening `quota_budget`'s primary key, which is the
row locked (`SELECT … FOR UPDATE`) on **every** Drahmi call, and adding qualifiers to
every class that injects `QuotaGovernor` by type. That is a change to the most
consequential code path in the service, made to support 2 calls a day.

## Consequences

**Duplicated structure.** Two governors, two budget tables, two entities with near-identical
fields. If a third provider arrives, the duplication becomes the stronger argument and
generalising is worth revisiting — with two concrete cases to design against rather than
one plus a guess.

**Two gauges, two breakers.** `quantpulse.alphavantage.quota.*` sits next to
`quantpulse.quota.*`; an Alpha Vantage outage cannot open Drahmi's breaker, nor the
reverse. That independence is wanted.

**Name-based bean resolution is now load-bearing.** Two `RestClient` beans exist;
`DrahmiHttpProvider` and `AlphaVantageHttpClient` each receive the right one only because
their constructor parameter names match the bean names, which works because the reactor
compiles with `-parameters` (ADR-001). Dropping that flag would fail startup with an
ambiguous-bean error — loudly, at least, rather than by wiring the wrong client.

**FX lives in qp-marketdata.** `market.fx_rate` is behind the same schema isolation as every
other market table (ADR-002). A service that needs conversion calls qp-marketdata over HTTP,
exactly as qp-portfolio already does for OHLCV history.
