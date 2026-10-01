# ADR-005 — Forecasts are distributions with calibration evidence, never point predictions

- **Status:** Accepted
- **Date:** 2026-08-05

## Context

"Predict the stock price" is the obvious feature request for a market platform, and it is
the one most likely to be built badly. A model that outputs a single number looks
impressive in a demo, cannot be falsified in a demo, and is worthless in use.

## Decision

Three rules, enforced in the API contract rather than in documentation:

1. **Every forecast returns a distribution.** Percentile bands over the horizon, plus
   probabilities of specific outcomes — P(above spot), P(down more than 10%). Never a
   single expected price.
2. **Every forecast ships with a calibration backtest.** Walk-forward, out-of-sample:
   parameters are re-fit on a rolling window using only data before each cut-off, the
   predicted interval is compared against what actually happened, and empirical coverage is
   reported against nominal. The response says outright whether the model is well
   calibrated, conservative, or overconfident.
3. **Assumptions ship in the payload.** GBM assumes lognormal returns and constant
   volatility. Both are false for real equities. That is stated in the response body, not
   buried in a README.

## Rationale

The backtest is the entire point. Anything can emit a confident-looking number; the
question worth answering is *how do you know it is any good*, and coverage on held-out data
answers it with evidence.

It also earns its keep by finding real problems. The first implementation used a plain
sample standard deviation for volatility, and the backtest showed 90% intervals containing
100% of outcomes on ATW — safe and useless. The diagnosis was volatility clustering: an
equal-weighted σ stays elevated for the whole window after a shock, long after conditions
normalise. Switching to EWMA (RiskMetrics, λ=0.94) improved calibration on every
instrument tested. Both estimators are still reported side by side, so the choice is
visibly evidence-based.

Where it still fails is also reported. On the highest-volatility instrument the intervals
remain overconfident even with EWMA — the expected consequence of a lognormal assumption
meeting fat tails. The fix is a Student-t or GARCH volatility model; until that exists, the
limitation is stated rather than hidden.

## Consequences

- The UI cannot show "target price: 720 MAD". It shows a cone and a coverage table. That is
  harder to read and it is correct.
- Two backtests run per forecast request (one per estimator), which is most of the endpoint's
  latency. Worth it: the comparison is the argument.
- **Overconfident** is flagged as the dangerous direction explicitly, because a risk limit
  built on an interval that is too narrow is breached more often than its owner believes.

## Alternatives rejected

- **Point forecast with a confidence score.** A single number plus a number about the number.
  Unfalsifiable in exactly the same way.
- **An LSTM or gradient-boosted price predictor.** Would look more sophisticated and be
  harder to defend: on one year of daily data for a thin market, such a model overfits, and
  without a calibration story it says nothing about uncertainty at all. Complexity is not
  the missing ingredient here — evidence is.
- **No forecasting.** Avoids the risk but ducks a question the domain genuinely asks.
