"""
The quant core. Pure functions on NumPy/pandas, no HTTP, so everything is easy to test.
Each function says what it assumes, since the assumptions change the result.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats
from scipy.cluster import hierarchy
from scipy.spatial.distance import squareform
from sklearn.decomposition import PCA

TRADING_DAYS = 252


# --- Correlation ---

def correlation_matrix(returns: pd.DataFrame, method: str = "pearson") -> pd.DataFrame:
    """
    Correlation of daily log returns between each pair.

    min_periods avoids a fake +-0.99 from two stocks that only overlap on a few days
    (common here, some stocks don't trade for weeks).

    Spearman is available because one crash day can dominate Pearson. If the two
    disagree, the relationship is probably one outlier.
    """
    return returns.corr(method=method, min_periods=30)


def cluster_order(corr: pd.DataFrame) -> tuple[list[str], list[dict]]:
    """
    Reorders stocks so correlated ones end up next to each other (hierarchical clustering).

    An alphabetical heatmap shows nothing. Ordered this way you see banks next to banks,
    without telling the algorithm about sectors. If the groups match the real sectors,
    the structure is real.

    Distance = sqrt(0.5 * (1 - rho)): 0 for perfectly correlated, 1 for opposite.
    Ward linkage keeps clusters tight.
    """
    if corr.empty or len(corr) < 3:
        return list(corr.columns), []

    filled = corr.fillna(0.0).clip(-1.0, 1.0)
    distance = np.sqrt(0.5 * (1.0 - filled.values))
    np.fill_diagonal(distance, 0.0)
    # squareform needs an exactly symmetric matrix, rounding can break that.
    distance = (distance + distance.T) / 2.0

    linkage = hierarchy.linkage(squareform(distance, checks=False), method="ward")
    order = hierarchy.leaves_list(linkage)
    ordered = [corr.columns[i] for i in order]

    # Cut into a few groups so the UI can label them.
    n_clusters = min(6, max(2, len(ordered) // 6))
    labels = hierarchy.fcluster(linkage, t=n_clusters, criterion="maxclust")
    groups = [
        {"ticker": corr.columns[i], "cluster": int(labels[i])}
        for i in range(len(corr.columns))
    ]
    return ordered, groups


def average_pairwise_correlation(corr: pd.DataFrame) -> float:
    """
    Average correlation between stocks: how much the market moves as one.
    High means diversifying inside this market doesn't help much.
    """
    if corr.empty or len(corr) < 2:
        return float("nan")
    values = corr.values.astype(float)
    mask = ~np.eye(len(values), dtype=bool)
    off = values[mask]
    off = off[~np.isnan(off)]
    return float(off.mean()) if off.size else float("nan")


# --- Factors (PCA) ---

def pca_factors(returns: pd.DataFrame, n_components: int = 5) -> dict:
    """
    PCA on the return matrix.

    PC1 is basically the market. If it explains 45% of the variance, almost half of each
    stock's daily move is just "the market moved", which limits diversification here.
    The next components usually separate sectors.

    Returns are standardised first, otherwise PC1 is just the most volatile stock.
    """
    clean = returns.dropna(axis=1, thresh=int(len(returns) * 0.8)).ffill().dropna()
    if clean.shape[1] < 3 or clean.shape[0] < 30:
        return {"available": False, "reason": "not enough complete series for PCA"}

    standardised = (clean - clean.mean()) / clean.std(ddof=1)
    standardised = standardised.replace([np.inf, -np.inf], np.nan).dropna(axis=1)

    k = min(n_components, standardised.shape[1])
    model = PCA(n_components=k)
    model.fit(standardised.values)

    explained = model.explained_variance_ratio_
    loadings = pd.DataFrame(
        model.components_.T, index=standardised.columns,
        columns=[f"PC{i + 1}" for i in range(k)]
    )

    return {
        "available": True,
        "instruments": int(standardised.shape[1]),
        "sessions": int(standardised.shape[0]),
        "explainedVariance": [round(float(v), 6) for v in explained],
        "cumulativeExplained": [round(float(v), 6) for v in np.cumsum(explained)],
        # PC1 loadings almost all have the same sign, that's the market factor.
        "pc1Loadings": {
            t: round(float(v), 6)
            for t, v in loadings["PC1"].sort_values(ascending=False).items()
        },
        "interpretation": _interpret_pc1(explained[0] if len(explained) else float("nan")),
    }


def _interpret_pc1(share: float) -> str:
    if np.isnan(share):
        return "unavailable"
    pct = share * 100
    if share > 0.5:
        return (f"PC1 explains {pct:.1f}% of variance — this market moves largely as a "
                "single block, so diversifying within it buys little.")
    if share > 0.3:
        return (f"PC1 explains {pct:.1f}% of variance — a substantial common market "
                "factor, with meaningful idiosyncratic movement remaining.")
    return (f"PC1 explains {pct:.1f}% of variance — unusually weak common factor; "
            "instrument-specific drivers dominate.")


# --- Forecast ranges ---

def monte_carlo_forecast(
    closes: pd.Series,
    horizon_days: int = 60,
    n_sims: int = 10_000,
    seed: int = 42,
    vol_estimator: str = "sample",
) -> dict:
    """
    Simulates possible future prices with geometric Brownian motion.

    Not a prediction. It shows the range of prices that fits how the stock behaved before.

    S(t+1) = S(t) * exp((mu - sigma^2/2) * dt + sigma * sqrt(dt) * Z), Z ~ N(0,1)
    mu and sigma come from past log returns. The -sigma^2/2 term (Ito correction) is
    needed, without it the median path is biased up.

    Limits (also returned in the response):
      * constant volatility, but real volatility comes in waves
      * lognormal, but real returns have fatter tails
      * mu from one year of data is very noisy, so long horizons spread out fast

    Seeded so the same inputs always give the same bands.
    """
    prices = closes.dropna().astype(float)
    if len(prices) < 30:
        return {"available": False, "reason": "need at least 30 sessions"}

    log_ret = np.log(prices / prices.shift(1)).dropna().values
    if log_ret.size < 20:
        return {"available": False, "reason": "not enough return observations"}

    mu = float(np.mean(log_ret))
    sigma = (ewma_volatility(log_ret) if vol_estimator == "ewma"
             else float(np.std(log_ret, ddof=1)))
    if not np.isfinite(sigma) or sigma <= 0:
        return {"available": False, "reason": "volatility estimate is degenerate"}
    s0 = float(prices.iloc[-1])

    rng = np.random.default_rng(seed)
    shocks = rng.standard_normal((n_sims, horizon_days))
    drift = mu - 0.5 * sigma**2
    # Sum the log steps and exponentiate once: more stable and faster than multiplying step by step.
    log_paths = np.cumsum(drift + sigma * shocks, axis=1)
    paths = s0 * np.exp(log_paths)

    percentiles = [5, 10, 25, 50, 75, 90, 95]
    bands = {f"p{p}": np.percentile(paths, p, axis=0).round(2).tolist() for p in percentiles}

    terminal = paths[:, -1]
    return {
        "available": True,
        "spot": round(s0, 2),
        "horizonDays": horizon_days,
        "simulations": n_sims,
        "dailyDriftLog": round(mu, 8),
        "dailyVolatility": round(sigma, 8),
        "volEstimator": vol_estimator,
        "annualisedVolatility": round(sigma * np.sqrt(TRADING_DAYS), 6),
        "bands": bands,
        "terminal": {
            "mean": round(float(terminal.mean()), 2),
            "median": round(float(np.median(terminal)), 2),
            "p5": round(float(np.percentile(terminal, 5)), 2),
            "p95": round(float(np.percentile(terminal, 95)), 2),
            # Chance of losing money, which a single forecast number can't show.
            "probAboveSpot": round(float((terminal > s0).mean()), 4),
            "probDown10Pct": round(float((terminal < s0 * 0.9).mean()), 4),
            "probUp10Pct": round(float((terminal > s0 * 1.1).mean()), 4),
        },
        "assumptions": [
            "Geometric Brownian motion: returns lognormal, volatility constant.",
            "Real equity returns are fat-tailed and volatility clusters, so tail risk here "
            "is understated.",
            "Drift is estimated from the sample and is statistically weak over one year; "
            "treat the median path as uninformative and the width as the useful output.",
            "This is a distribution of outcomes consistent with past behaviour, not a "
            "forecast of what will happen.",
        ],
    }


def ewma_volatility(log_ret: np.ndarray, lam: float = 0.94) -> float:
    """
    Exponentially weighted volatility (RiskMetrics, lambda = 0.94 for daily data).

    A normal std dev weights a return from 120 days ago like yesterday's. But volatility
    comes in waves, so after a shock the plain estimate stays high for too long and the
    bands are too wide. EWMA gives recent days more weight (memory of ~1/(1-lambda) = 17 days).

    The backtest showed why: with constant sigma, ATW's 90% intervals contained 100% of
    outcomes. Safe, but useless.
    """
    if log_ret.size < 2:
        return float("nan")
    weights = lam ** np.arange(log_ret.size - 1, -1, -1)
    weights /= weights.sum()
    mean = float(np.sum(weights * log_ret))
    var = float(np.sum(weights * (log_ret - mean) ** 2))
    return float(np.sqrt(var))


def backtest_calibration(
    closes: pd.Series,
    horizon_days: int = 20,
    train_window: int = 120,
    confidence_levels: tuple[int, ...] = (50, 80, 90),
    seed: int = 42,
    vol_estimator: str = "sample",
) -> dict:
    """
    Walk-forward test: are the forecast intervals reliable?

      for each cut-off date:
        1. fit mu and sigma on the train_window days before it only
        2. build the interval for horizon_days ahead
        3. check where the price actually ended
        4. record if it was inside

    An 80% interval should contain the result about 80% of the time. 95% means too wide,
    55% means overconfident (the dangerous case, it underestimates risk).

    Only data before the cut-off is used. Fitting on everything first would be lookahead bias.
    """
    prices = closes.dropna().astype(float).reset_index(drop=True)
    needed = train_window + horizon_days + 10
    if len(prices) < needed:
        return {"available": False,
                "reason": f"need at least {needed} sessions, have {len(prices)}"}

    rng = np.random.default_rng(seed)
    hits = {level: 0 for level in confidence_levels}
    trials = 0
    errors = []

    for cut in range(train_window, len(prices) - horizon_days):
        train = prices.iloc[cut - train_window:cut]
        log_ret = np.log(train / train.shift(1)).dropna().values
        if log_ret.size < 30:
            continue

        mu = float(np.mean(log_ret))
        sigma = (ewma_volatility(log_ret) if vol_estimator == "ewma"
                 else float(np.std(log_ret, ddof=1)))
        if not np.isfinite(sigma) or sigma <= 0:
            continue

        s0 = float(prices.iloc[cut - 1])
        actual = float(prices.iloc[cut - 1 + horizon_days])

        # Exact lognormal quantiles, same result as simulating but much faster.
        total_drift = (mu - 0.5 * sigma**2) * horizon_days
        total_sd = sigma * np.sqrt(horizon_days)

        for level in confidence_levels:
            tail = (1 - level / 100) / 2
            lo = s0 * np.exp(total_drift + stats.norm.ppf(tail) * total_sd)
            hi = s0 * np.exp(total_drift + stats.norm.ppf(1 - tail) * total_sd)
            if lo <= actual <= hi:
                hits[level] += 1

        expected_median = s0 * np.exp(total_drift)
        errors.append((actual - expected_median) / s0)
        trials += 1

    if trials == 0:
        return {"available": False, "reason": "no valid backtest windows"}

    coverage = {
        f"nominal{level}": {
            "nominal": level / 100,
            "empirical": round(hits[level] / trials, 4),
            "verdict": _calibration_verdict(hits[level] / trials, level / 100),
        }
        for level in confidence_levels
    }

    errors = np.array(errors)
    return {
        "available": True,
        "trials": trials,
        "horizonDays": horizon_days,
        "trainWindow": train_window,
        "volEstimator": vol_estimator,
        "coverage": coverage,
        "medianRelativeError": round(float(np.median(errors)), 6),
        "meanAbsoluteRelativeError": round(float(np.mean(np.abs(errors))), 6),
        "method": (
            "Walk-forward out-of-sample. Parameters are re-fit on a rolling window using "
            "only data prior to each cut-off, so no future information reaches the fit."
        ),
    }


def _calibration_verdict(empirical: float, nominal: float) -> str:
    gap = empirical - nominal
    if abs(gap) <= 0.05:
        return "well calibrated"
    if gap > 0:
        return (f"conservative — intervals too wide by {gap * 100:.1f}pp, so they are "
                "safe but uninformative")
    return (f"overconfident — intervals too narrow by {abs(gap) * 100:.1f}pp; this "
            "understates risk, the dangerous direction")


# --- Portfolio optimisation ---

def efficient_frontier(
    returns: pd.DataFrame,
    n_portfolios: int = 4000,
    risk_free: float = 0.0,
    seed: int = 42,
) -> dict:
    """
    Markowitz efficient frontier, by trying random weights.

    Random sampling instead of a solver: simple, no extra dependency, and the UI can show
    the whole cloud of portfolios. For 10-20 stocks, long only, it gets close enough.

    Careful: mean-variance is very sensitive to expected returns, which are estimated
    badly. A small change can flip the weights. That's why we also return the min-variance
    portfolio, which only needs the covariance (estimated much better).
    """
    clean = returns.dropna(axis=1, thresh=int(len(returns) * 0.8)).ffill().dropna()
    if clean.shape[1] < 2 or clean.shape[0] < 60:
        return {"available": False, "reason": "need at least 2 series and 60 sessions"}

    tickers = list(clean.columns)
    mean_daily = clean.mean().values
    cov_daily = clean.cov().values

    annual_return = mean_daily * TRADING_DAYS
    annual_cov = cov_daily * TRADING_DAYS

    rng = np.random.default_rng(seed)
    n = len(tickers)
    # Dirichlet samples the weights evenly. Normalising uniform draws would favour the centre.
    weights = rng.dirichlet(np.ones(n), size=n_portfolios)

    port_return = weights @ annual_return
    port_var = np.einsum("ij,jk,ik->i", weights, annual_cov, weights)
    port_vol = np.sqrt(np.maximum(port_var, 0))

    with np.errstate(divide="ignore", invalid="ignore"):
        sharpe = np.where(port_vol > 0, (port_return - risk_free) / port_vol, np.nan)

    max_sharpe_idx = int(np.nanargmax(sharpe))
    min_vol_idx = int(np.argmin(port_vol))

    # Send fewer points, the browser can't usefully draw 4000.
    sample = rng.choice(n_portfolios, size=min(600, n_portfolios), replace=False)

    return {
        "available": True,
        "tickers": tickers,
        "sessions": int(clean.shape[0]),
        "cloud": [
            {"vol": round(float(port_vol[i]), 6),
             "ret": round(float(port_return[i]), 6),
             "sharpe": round(float(sharpe[i]), 4) if np.isfinite(sharpe[i]) else None}
            for i in sample
        ],
        "maxSharpe": _portfolio_summary(tickers, weights[max_sharpe_idx],
                                        port_return[max_sharpe_idx],
                                        port_vol[max_sharpe_idx], sharpe[max_sharpe_idx]),
        "minVariance": _portfolio_summary(tickers, weights[min_vol_idx],
                                          port_return[min_vol_idx],
                                          port_vol[min_vol_idx], sharpe[min_vol_idx]),
        "caveat": (
            "Mean-variance weights are highly sensitive to estimated expected returns, "
            "which carry large standard errors. The minimum-variance portfolio depends "
            "only on the covariance matrix and is far more stable out of sample — compare "
            "the two before trusting either."
        ),
    }


def _portfolio_summary(tickers, w, ret, vol, sharpe) -> dict:
    weights = {t: round(float(x), 6) for t, x in zip(tickers, w) if x > 0.005}
    return {
        "expectedReturn": round(float(ret), 6),
        "volatility": round(float(vol), 6),
        "sharpe": round(float(sharpe), 4) if np.isfinite(sharpe) else None,
        "weights": dict(sorted(weights.items(), key=lambda kv: -kv[1])),
    }


# --- Anomalies ---

def detect_anomalies(df: pd.DataFrame, z_threshold: float = 3.0) -> dict:
    """
    Finds unusual sessions.

    Uses the modified z-score (median and MAD) instead of mean and std dev, because a big
    outlier raises the std dev and can hide itself. 0.6745 makes it comparable to a normal
    z-score.

    Volume spikes are flagged separately: a price move with no volume is often a thin print,
    a volume spike with no price move is usually a block trade.
    """
    if df is None or df.empty or len(df) < 30:
        return {"available": False, "reason": "need at least 30 sessions"}

    data = df.copy()
    # Counted on all the input, before dropping the first row (no return). Otherwise a
    # zero-volume first day would be missed.
    zero_volume_sessions = int((data["volume"] == 0).sum())

    data["ret"] = np.log(data["close"] / data["close"].shift(1))
    data = data.dropna(subset=["ret"])

    ret = data["ret"].values
    median = float(np.median(ret))
    mad = float(np.median(np.abs(ret - median)))
    if mad == 0:
        return {"available": False, "reason": "zero MAD — series is nearly constant"}

    modified_z = 0.6745 * (ret - median) / mad

    vol = data["volume"].values.astype(float)
    nonzero = vol[vol > 0]
    vol_median = float(np.median(nonzero)) if nonzero.size else 0.0
    vol_mad = float(np.median(np.abs(nonzero - vol_median))) if nonzero.size else 0.0
    vol_z = (0.6745 * (vol - vol_median) / vol_mad) if vol_mad > 0 else np.zeros_like(vol)

    events = []
    for i in range(len(data)):
        price_flag = abs(modified_z[i]) >= z_threshold
        volume_flag = vol_z[i] >= z_threshold
        if not (price_flag or volume_flag):
            continue
        events.append({
            "date": data["date"].iloc[i].strftime("%Y-%m-%d"),
            "close": round(float(data["close"].iloc[i]), 2),
            "returnPct": round(float(ret[i]) * 100, 3),
            "priceZ": round(float(modified_z[i]), 2),
            "volume": int(vol[i]),
            "volumeZ": round(float(vol_z[i]), 2),
            "kind": ("price and volume" if price_flag and volume_flag
                     else "price move" if price_flag else "volume spike"),
        })

    return {
        "available": True,
        "sessions": int(len(data)),
        "zThreshold": z_threshold,
        "anomalies": sorted(events, key=lambda e: e["date"], reverse=True)[:50],
        "count": len(events),
        "zeroVolumeSessions": zero_volume_sessions,
        "method": ("Modified z-score on median/MAD. Robust to the outliers being detected, "
                   "unlike a mean/σ z-score where an extreme move inflates the threshold "
                   "that should have caught it."),
    }


# --- Sectors ---

def sector_statistics(
    returns: pd.DataFrame,
    sector_of: dict[str, str],
    closes: pd.DataFrame,
) -> list[dict]:
    """
    Risk and return per sector.

    Each sector is an equal-weighted portfolio of its stocks. Averaging the stocks'
    volatilities would always be too high since it ignores diversification between them.
    """
    by_sector: dict[str, list[str]] = {}
    for ticker in returns.columns:
        sector = sector_of.get(ticker)
        if sector:
            by_sector.setdefault(sector, []).append(ticker)

    out = []
    for sector, members in by_sector.items():
        if len(members) == 0:
            continue
        sub = returns[members]
        # Equal-weighted sector returns.
        portfolio = sub.mean(axis=1, skipna=True).dropna()
        if len(portfolio) < 30:
            continue

        vol = float(portfolio.std(ddof=1) * np.sqrt(TRADING_DAYS))
        total_ret = float(np.exp(portfolio.sum()) - 1)
        mean_ann = float(portfolio.mean() * TRADING_DAYS)

        # Drawdown is computed on an index built from the sector's returns, not on average prices.
        # MNG is around 1,300 MAD and CMT around 1.00, so an average price is basically MNG.
        # That gave a 90.7% drawdown for MINES, about 10x the real value.
        index = np.exp(portfolio.cumsum())
        drawdown = float(_max_drawdown(index))

        out.append({
            "sector": sector,
            "instruments": len(members),
            "annualisedVolatility": round(vol, 6),
            "totalReturn": round(total_ret, 6),
            "annualisedReturn": round(mean_ann, 6),
            "sharpe": round(mean_ann / vol, 4) if vol > 0 else None,
            "maxDrawdown": round(drawdown, 6),
            "avgPairwiseCorrelation": (
                round(average_pairwise_correlation(sub.corr(min_periods=30)), 4)
                if len(members) > 1 else None
            ),
            "members": members,
        })

    return sorted(out, key=lambda s: -(s["sharpe"] or -99))


def _max_drawdown(series: pd.Series) -> float:
    if series.empty:
        return 0.0
    running_max = series.cummax()
    drawdown = (running_max - series) / running_max
    return float(drawdown.max())
