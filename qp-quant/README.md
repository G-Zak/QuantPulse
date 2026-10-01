# qp-quant

Quantitative analytics for the Casablanca Stock Exchange.

Python rather than Java, deliberately: this is vectorised linear algebra over return
matrices — eigendecomposition, covariance estimation, constrained optimisation, Monte
Carlo. NumPy/SciPy do that in a few lines and at BLAS speed. Writing it in Java would
mean reimplementing PCA and a quadratic solver by hand for no benefit. Picking the right
runtime per workload is the point of a polyglot architecture, not a compromise.

It reads OHLCV over qp-marketdata's public HTTP API — never its database, which it has no
grant on.

## What it computes

| Endpoint | What it answers |
|---|---|
| `GET /analysis/correlation` | How do Moroccan equities co-move? Correlation matrix reordered by hierarchical clustering so blocks are visible |
| `GET /analysis/sectors` | Risk/return decomposition per sector |
| `GET /analysis/factors` | PCA on returns — how much of the market is one common factor? |
| `GET /analysis/forecast/{ticker}` | Monte Carlo distribution of future prices **plus a calibration backtest** |
| `POST /analysis/optimise` | Markowitz efficient frontier, min-variance and max-Sharpe portfolios |
| `GET /analysis/anomalies/{ticker}` | Statistically unusual sessions |

## On forecasting, honestly

The forecast endpoint returns a **distribution**, never a point prediction, and it ships
with a backtest that measures whether its own intervals are calibrated — i.e. whether the
realised price actually landed inside the 80% band about 80% of the time on held-out
history.

That backtest is the point. Any model can emit a confident-looking number; the question an
interviewer should ask is "how do you know it's any good?", and this answers it with
evidence rather than assertion. Where the model is miscalibrated, the response says so.

Assumptions are stated in the response payload, not buried: GBM assumes lognormal returns
and constant volatility. Real equity returns have fatter tails and volatility clusters, so
the bands understate tail risk. That is a known limitation, reported alongside the numbers.
