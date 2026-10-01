# ADR-004 — A separate Python service for quantitative analytics

- **Status:** Accepted
- **Date:** 2026-08-05

## Context

The platform needed cross-sectional market analysis: correlation structure across the
listed universe, PCA factor extraction, portfolio optimisation, distributional forecasting
with calibration testing. Everything else in QuantPulse is Java.

## Decision

A separate service, `qp-quant`, written in Python with FastAPI, NumPy, pandas, SciPy and
scikit-learn. It reads OHLCV over qp-marketdata's HTTP API and owns no database.

## Rationale

This work is vectorised linear algebra over return matrices — eigendecomposition,
covariance estimation, quantile computation over large simulated arrays. NumPy expresses
it in a few lines and executes it through BLAS. The Java equivalent would mean either
pulling in a numerical stack most teams do not run, or hand-writing PCA and a hierarchical
clustering routine, for no benefit and considerable risk of getting the maths subtly wrong.

The reverse argument holds just as firmly for the rest of the system: the trade ledger and
its transactional guarantees belong in Java, and would be worse in Python.

Choosing a runtime per workload is the actual justification for a polyglot architecture.
"We use Python because it's popular for data science" is not a reason; "the operation is a
symmetric eigendecomposition and LAPACK already implements it correctly" is.

It stays a **separate service** rather than a library because its failure and resource
profile differ from everything else: a 40×40 correlation matrix or a walk-forward backtest
is seconds of CPU, and that must not occupy a request thread in the service handling price
ingestion. The BFF gives it a 90-second read timeout where the Java services get 5.

## Consequences

**Accepted costs.** A second toolchain to install, build and deploy. A second test runner.
An HTTP hop for data that qp-marketdata could otherwise pass in-process. Latency on
analytics endpoints measured in seconds rather than milliseconds.

**Mitigations.** The analytics core (`app/analytics.py`) is pure functions over NumPy with
no HTTP and no framework, so it is tested without a running stack — 20 tests cover the
maths, including one that pins the Itô correction and one that pins the calibration
backtest against a series that genuinely is GBM.

**Not addressed yet.** No caching: every request recomputes. Acceptable at this scale
(seconds, low traffic) and the honest answer to "why is this slow?" is that results are
never stale. A production version would cache by (universe, range, as-of-date).

## Alternatives rejected

- **Compute it in qp-portfolio (Java).** Would need a numerical library, and the
  single-instrument risk metrics that already live there are simple enough not to warrant
  one. The cross-sectional work is a different problem.
- **A notebook only, no service.** Not reachable from the dashboard, and notebooks drift
  out of sync with the code they document. The notebook now calls the same service the UI
  does, so both cannot disagree.
- **An off-the-shelf analytics platform.** The point is to demonstrate the methodology, not
  to integrate a vendor.
