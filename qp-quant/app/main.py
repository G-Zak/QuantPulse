"""
qp-quant: FastAPI endpoints over the analytics code.

Each endpoint gets data, calls a pure function from analytics, and returns the result.
The logic is in analytics.py so it can be tested without network or framework.
"""

from __future__ import annotations

import os
from typing import Optional

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .analytics import (
    average_pairwise_correlation,
    backtest_calibration,
    cluster_order,
    correlation_matrix,
    detect_anomalies,
    efficient_frontier,
    monte_carlo_forecast,
    pca_factors,
    sector_statistics,
)
from .data import MarketDataClient, close_matrix, log_returns
from .income import (
    INDICATIVE_FX,
    annual_dividends,
    growth_analysis,
    payout_ratio,
    project_income,
    screen_income,
    trailing_twelve_months,
    yield_trap_check,
)

app = FastAPI(
    title="QuantPulse Quant Analytics",
    version="2.0.0",
    description=(
        "Quantitative analysis of the Casablanca Stock Exchange. Forecast endpoints "
        "return distributions with calibration evidence, never point predictions."
    ),
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=os.getenv("ALLOWED_ORIGINS", "http://localhost:3002,http://localhost:3000").split(","),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

client = MarketDataClient()

# Limit the number of stocks: the correlation matrix grows with n^2, and over ~60
# the heatmap is unreadable anyway.
MAX_UNIVERSE = 60


@app.get("/health")
async def health():
    return {"status": "UP", "service": "qp-quant"}


async def _universe(limit: int) -> tuple[list[str], dict[str, str]]:
    """Biggest instruments by market cap, with their sectors."""
    instruments = await client.instruments()
    if instruments.empty:
        raise HTTPException(503, "qp-marketdata unavailable or has no instruments")
    head = instruments.head(min(limit, MAX_UNIVERSE))
    sector_of = {
        r["ticker"]: r.get("sector")
        for _, r in head.iterrows()
        if r.get("sector")
    }
    return list(head["ticker"]), sector_of


@app.get("/analysis/correlation")
async def correlation(
    range_code: str = Query("1Y", alias="range"),
    limit: int = Query(40, ge=3, le=MAX_UNIVERSE),
    method: str = Query("pearson", pattern="^(pearson|spearman)$"),
):
    """
    Correlation matrix, reordered by hierarchical clustering.

    The clustering doesn't know the sectors, so if its groups match them, that means something.
    """
    tickers, sector_of = await _universe(limit)
    histories = await client.histories(tickers, range_code)
    closes = close_matrix(histories)
    if closes.empty:
        raise HTTPException(422, "no usable price series")

    rets = log_returns(closes)
    corr = correlation_matrix(rets, method=method)
    ordered, clusters = cluster_order(corr)

    cluster_of = {c["ticker"]: c["cluster"] for c in clusters}
    return {
        "range": range_code,
        "method": method,
        "instruments": list(corr.columns),
        "clusterOrder": ordered,
        "clusters": [
            {"ticker": t, "cluster": cluster_of.get(t), "sector": sector_of.get(t)}
            for t in ordered
        ],
        "matrix": [
            [None if pd.isna(v) else round(float(v), 4) for v in corr.loc[a, ordered]]
            for a in ordered
        ],
        "averagePairwiseCorrelation": round(average_pairwise_correlation(corr), 4),
        "excluded": sorted(set(tickers) - set(corr.columns)),
        "note": (
            "Series are aligned on session date before differencing. Pairing by array "
            "position would correlate different days for instruments that do not trade "
            "every session — wrong, and invisible in the output."
        ),
    }


@app.get("/analysis/factors")
async def factors(
    range_code: str = Query("1Y", alias="range"),
    limit: int = Query(40, ge=5, le=MAX_UNIVERSE),
    components: int = Query(5, ge=2, le=10),
):
    """PCA on returns: how much of this market moves as one?"""
    tickers, sector_of = await _universe(limit)
    histories = await client.histories(tickers, range_code)
    closes = close_matrix(histories)
    if closes.empty:
        raise HTTPException(422, "no usable price series")

    result = pca_factors(log_returns(closes), n_components=components)
    result["range"] = range_code
    result["sectors"] = sector_of
    return result


@app.get("/analysis/sectors")
async def sectors(
    range_code: str = Query("1Y", alias="range"),
    limit: int = Query(60, ge=5, le=MAX_UNIVERSE),
):
    """
    Risk/return per sector, each sector treated as an equal-weighted portfolio
    (not an average of its stocks' stats).
    """
    tickers, sector_of = await _universe(limit)
    histories = await client.histories(tickers, range_code)
    closes = close_matrix(histories)
    if closes.empty:
        raise HTTPException(422, "no usable price series")

    stats = sector_statistics(log_returns(closes), sector_of, closes)
    return {"range": range_code, "sectors": stats, "sectorCount": len(stats)}


@app.get("/analysis/forecast/{ticker}")
async def forecast(
    ticker: str,
    horizon: int = Query(60, ge=5, le=252),
    sims: int = Query(10_000, ge=1000, le=100_000),
    range_code: str = Query("1Y", alias="range"),
    backtest: bool = Query(True),
    vol_estimator: str = Query("ewma", pattern="^(sample|ewma)$", alias="volEstimator"),
):
    """
    Forecast range, plus by default a backtest showing whether its intervals are reliable.
    The coverage numbers are what make the bands believable.
    """
    df = await client.history(ticker.upper(), range_code)
    if df.empty:
        raise HTTPException(404, f"no history for {ticker}")

    closes = df.set_index("date")["close"].astype(float)
    result = monte_carlo_forecast(closes, horizon_days=horizon, n_sims=sims,
                                  vol_estimator=vol_estimator)
    if not result.get("available"):
        raise HTTPException(422, result.get("reason", "forecast unavailable"))

    result["ticker"] = ticker.upper()
    result["range"] = range_code
    result["historyEnd"] = df["date"].iloc[-1].strftime("%Y-%m-%d")

    if backtest:
        # Shorter backtest horizon so one year of data gives enough windows.
        h = min(20, horizon)
        result["calibration"] = backtest_calibration(
            closes, horizon_days=h, train_window=120, vol_estimator=vol_estimator
        )
        # Both estimators side by side, so the choice is based on measured calibration.
        # On fat-tailed stocks it also shows where EWMA isn't enough.
        other = "sample" if vol_estimator == "ewma" else "ewma"
        result["calibrationComparison"] = {
            vol_estimator: result["calibration"].get("coverage"),
            other: backtest_calibration(
                closes, horizon_days=h, train_window=120, vol_estimator=other
            ).get("coverage"),
        }
    return result


class IncomeRequest(BaseModel):
    """amount is in currency; the stock trades in MAD."""
    ticker: str
    amount: float = Field(..., gt=0)
    currency: str = "MAD"
    fx_rate: float | None = Field(None, alias="fxRate", gt=0)
    horizon_years: int = Field(5, alias="horizonYears", ge=1, le=30)

    model_config = {"populate_by_name": True}


async def _price_change_1y(ticker: str) -> float | None:
    """One-year price change, to tell a high yield from a falling price."""
    df = await client.history(ticker, "1Y")
    if df.empty or len(df) < 2:
        return None
    first, last = float(df["close"].iloc[0]), float(df["close"].iloc[-1])
    return (last - first) / first if first else None


@app.post("/analysis/income")
async def income(req: IncomeRequest):
    """
    Projects dividend income for an investment.

    Three scenarios (flat, historical growth, 30% cut) instead of one number.
    """
    ticker = req.ticker.upper()
    instrument = await client.instrument(ticker)
    if not instrument:
        raise HTTPException(404, f"unknown instrument {ticker}")

    dividends = await client.dividends(ticker)
    change = await _price_change_1y(ticker)

    # Market median yield, so "high yield" means high compared to the market.
    median_yield = await _market_median_yield()

    fx_rate, fx_source = req.fx_rate, None
    currency = req.currency.upper()
    if fx_rate is None and currency != "MAD":
        stored = await client.fx_quote("MAD", currency)
        if stored and stored.get("rate"):
            fx_rate = float(stored["rate"])
            fx_source = f"{stored.get('source', 'stored')} {stored.get('rateDate')}" + (
                " (stale)" if stored.get("stale") else "")

    result = project_income(
        amount=req.amount, currency=req.currency, instrument=instrument,
        dividends=dividends, price_change_1y=change, fx_rate=fx_rate, fx_source=fx_source,
        horizon_years=req.horizon_years, market_median_yield=median_yield,
    )
    if not result.get("available"):
        raise HTTPException(422, result.get("reason", "cannot project income"))
    result["priceChange1Y"] = round(change, 6) if change is not None else None
    result["marketMedianYield"] = round(median_yield, 6) if median_yield else None
    return result


async def _market_median_yield(limit: int = 30) -> float | None:
    tickers, _ = await _universe(limit)
    divs = await client.many_dividends(tickers)
    instruments = await client.instruments()
    prices = {r["ticker"]: r.get("price") for _, r in instruments.iterrows()}
    yields = []
    for t, rows in divs.items():
        price = prices.get(t)
        if not price or not rows:
            continue
        ttm = trailing_twelve_months(rows)
        if ttm > 0:
            yields.append(ttm / float(price))
    return float(np.median(yields)) if yields else None


@app.get("/analysis/income/screen")
async def income_screen(limit: int = Query(30, ge=5, le=MAX_UNIVERSE)):
    """
    Ranks stocks by dividend yield, with quality signals.

    Each row has consistency, number of cuts and trap level, because sorting on yield alone
    puts the companies most likely to cut at the top.
    """
    tickers, sector_of = await _universe(limit)
    divs = await client.many_dividends(tickers)
    instruments = await client.instruments()
    by_ticker = {r["ticker"]: r for _, r in instruments.iterrows()}

    rows = []
    for t, dividend_rows in divs.items():
        inst = by_ticker.get(t)
        if inst is None or not dividend_rows:
            continue
        price = float(inst.get("price") or 0)
        if price <= 0:
            continue
        ttm = trailing_twelve_months(dividend_rows)
        if ttm <= 0:
            continue

        annual = annual_dividends(dividend_rows)
        growth = growth_analysis(annual)
        payout = payout_ratio(ttm, price, inst.get("peRatio"))
        current_yield = ttm / price

        rows.append({
            "ticker": t,
            "name": inst.get("name"),
            "sector": sector_of.get(t),
            "price": round(price, 2),
            "dividendPerShare": round(ttm, 4),
            "currentYield": round(current_yield, 6),
            "yearsPaid": growth.get("years"),
            "cuts": growth.get("cuts"),
            "cagr": growth.get("cagr"),
            "consistency": growth.get("consistency"),
            "payoutRatio": payout.get("payoutRatio") if payout.get("available") else None,
        })

    screen = screen_income(rows)
    if not screen.get("available"):
        raise HTTPException(422, screen.get("reason", "no dividend data"))

    # Trap level needs the market median, so it's done after.
    median = screen["medianYield"]
    for r in screen["instruments"]:
        r["trapLevel"] = yield_trap_check(
            r["currentYield"],
            {"cuts": r.get("cuts") or 0},
            None,
            {"available": r.get("payoutRatio") is not None,
             "payoutRatio": r.get("payoutRatio") or 0},
            median,
        )["level"]
    return screen


@app.get("/analysis/fx-rates")
async def fx_rates():
    """
    FX rates for income projections: 1 MAD in each currency.

    Uses rates stored by qp-marketdata (Alpha Vantage) when there are some, otherwise
    the indicative table. Each rate says where it comes from.
    """
    rates, sources = {"MAD": 1.0}, {"MAD": "identity"}
    for ccy, fallback in INDICATIVE_FX.items():
        if ccy == "MAD":
            continue
        stored = await client.fx_quote("MAD", ccy)
        if stored and stored.get("rate"):
            rates[ccy] = float(stored["rate"])
            sources[ccy] = f"{stored.get('source')} {stored.get('rateDate')}"
        else:
            rates[ccy] = fallback
            sources[ccy] = "indicative (2026-08)"
    return {
        "base": "MAD",
        "rates": rates,
        "sources": sources,
        "warning": "For a foreign investor, FX movement can exceed the entire dividend yield.",
    }


class OptimiseRequest(BaseModel):
    tickers: list[str] = Field(..., min_length=2, max_length=25)
    range_code: str = Field("1Y", alias="range")
    risk_free: float = 0.0

    model_config = {"populate_by_name": True}


@app.post("/analysis/optimise")
async def optimise(req: OptimiseRequest):
    """Markowitz efficient frontier for the chosen instruments."""
    symbols = [t.upper() for t in req.tickers]
    histories = await client.histories(symbols, req.range_code)
    closes = close_matrix(histories)
    if closes.empty or closes.shape[1] < 2:
        raise HTTPException(422, "need at least two instruments with usable history")

    result = efficient_frontier(log_returns(closes), risk_free=req.risk_free)
    if not result.get("available"):
        raise HTTPException(422, result.get("reason", "optimisation unavailable"))
    result["range"] = req.range_code
    result["requested"] = symbols
    return result


@app.get("/analysis/anomalies/{ticker}")
async def anomalies(
    ticker: str,
    range_code: str = Query("1Y", alias="range"),
    z: float = Query(3.0, ge=1.5, le=10.0),
):
    """Unusual sessions, using a modified z-score (median/MAD)."""
    df = await client.history(ticker.upper(), range_code)
    if df.empty:
        raise HTTPException(404, f"no history for {ticker}")

    result = detect_anomalies(df, z_threshold=z)
    if not result.get("available"):
        raise HTTPException(422, result.get("reason", "unavailable"))
    result["ticker"] = ticker.upper()
    result["range"] = range_code
    return result


@app.get("/analysis/market-report")
async def market_report(
    range_code: str = Query("1Y", alias="range"),
    limit: int = Query(40, ge=5, le=MAX_UNIVERSE),
):
    """
    Correlation, factors and sectors in one call, so the analysis page needs one request
    instead of three.
    """
    tickers, sector_of = await _universe(limit)
    histories = await client.histories(tickers, range_code)
    closes = close_matrix(histories)
    if closes.empty:
        raise HTTPException(422, "no usable price series")

    rets = log_returns(closes)
    corr = correlation_matrix(rets)
    ordered, clusters = cluster_order(corr)
    cluster_of = {c["ticker"]: c["cluster"] for c in clusters}

    return {
        "range": range_code,
        "universe": list(corr.columns),
        "sessions": int(len(rets)),
        "correlation": {
            "order": ordered,
            "matrix": [
                [None if pd.isna(v) else round(float(v), 4) for v in corr.loc[a, ordered]]
                for a in ordered
            ],
            "average": round(average_pairwise_correlation(corr), 4),
            "clusters": [
                {"ticker": t, "cluster": cluster_of.get(t), "sector": sector_of.get(t)}
                for t in ordered
            ],
        },
        "factors": pca_factors(rets),
        "sectors": sector_statistics(rets, sector_of, closes),
    }
