"""
Data access for qp-quant.

Reads OHLCV from qp-marketdata's API, not its database (qp_quant can't read the
market schema, ADR-002).

Everything returns pandas objects so the analysis code stays pure.
"""

from __future__ import annotations

import asyncio
import os
from datetime import date
from functools import lru_cache

import httpx
import numpy as np
import pandas as pd

MARKETDATA_URL = os.getenv("MARKETDATA_URL", "http://localhost:8081")

# Indices use a different path than instruments.
INDEX_CODES = {"MASI", "MASI20", "MADEX"}

# Trading days per year. The real ATW year has 245 sessions, so 252 is a convention
# and explains small differences with the vendor.
TRADING_DAYS = 252


class MarketDataClient:
    def __init__(self, base_url: str = MARKETDATA_URL, timeout: float = 20.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    async def _get(self, client: httpx.AsyncClient, path: str):
        try:
            r = await client.get(f"{self.base_url}{path}", timeout=self.timeout)
            r.raise_for_status()
            return r.json()
        except Exception:
            # One missing series shouldn't stop the whole study. The caller drops it and reports it.
            return None

    async def instruments(self) -> pd.DataFrame:
        async with httpx.AsyncClient() as client:
            rows = await self._get(client, "/api/v1/instruments")
        if not rows:
            return pd.DataFrame()
        return pd.DataFrame(rows)

    async def history(self, symbol: str, range_code: str = "1Y") -> pd.DataFrame:
        path = (
            f"/api/v1/indices/{symbol}/history?range={range_code}"
            if symbol.upper() in INDEX_CODES
            else f"/api/v1/instruments/{symbol}/history?range={range_code}"
        )
        async with httpx.AsyncClient() as client:
            rows = await self._get(client, path)
        return _to_frame(rows)

    async def dividends(self, ticker: str) -> list[dict]:
        async with httpx.AsyncClient() as client:
            rows = await self._get(client, f"/api/v1/instruments/{ticker}/dividends")
        return rows or []

    async def many_dividends(self, tickers: list[str]) -> dict[str, list[dict]]:
        """In parallel, for the market-wide income screen."""
        async with httpx.AsyncClient() as client:
            results = await asyncio.gather(*[
                self._get(client, f"/api/v1/instruments/{t}/dividends") for t in tickers
            ])
        return {t: (r or []) for t, r in zip(tickers, results)}

    async def instrument(self, ticker: str) -> dict | None:
        async with httpx.AsyncClient() as client:
            return await self._get(client, f"/api/v1/instruments/{ticker}")

    async def fx_quote(self, base: str, quote: str) -> dict | None:
        """Latest stored rate for a pair from qp-marketdata (Alpha Vantage), or None."""
        async with httpx.AsyncClient() as client:
            rows = await self._get(client, f"/api/v1/fx/rates?pairs={base}-{quote}")
        return rows[0] if rows else None

    async def histories(self, symbols: list[str], range_code: str = "1Y") -> dict[str, pd.DataFrame]:
        """
        Fetches many series in parallel.
        For a 60-stock correlation matrix that's the difference between working and a timeout.
        """
        async with httpx.AsyncClient() as client:
            paths = [
                (
                    s,
                    f"/api/v1/indices/{s}/history?range={range_code}"
                    if s.upper() in INDEX_CODES
                    else f"/api/v1/instruments/{s}/history?range={range_code}",
                )
                for s in symbols
            ]
            results = await asyncio.gather(*[self._get(client, p) for _, p in paths])
        return {sym: _to_frame(rows) for (sym, _), rows in zip(paths, results)}


def _to_frame(rows) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame(columns=["date", "open", "high", "low", "close", "volume"])
    df = pd.DataFrame(rows)
    df["date"] = pd.to_datetime(df["date"])
    return df.sort_values("date").reset_index(drop=True)


def close_matrix(histories: dict[str, pd.DataFrame], min_sessions: int = 60) -> pd.DataFrame:
    """
    Builds a table of closing prices by date, one column per symbol.

    1. Joined by date, not by position. Not every stock trades every day here, so
       stacking arrays would pair Monday's return of one stock with Tuesday's of another.
    2. Symbols with fewer than min_sessions are dropped and reported, too little data
       for a meaningful correlation.
    """
    frames = {}
    for symbol, df in histories.items():
        if df is None or df.empty or len(df) < min_sessions:
            continue
        frames[symbol] = df.set_index("date")["close"].astype(float)
    if not frames:
        return pd.DataFrame()
    # Outer join keeps every date; NaNs are handled in log_returns.
    return pd.DataFrame(frames).sort_index()


def log_returns(prices: pd.DataFrame) -> pd.DataFrame:
    """
    Daily log returns.

    Log returns add up over time, so sqrt(t) scaling for annualising works.
    Prices <= 0 become NaN instead of -inf.
    """
    clean = prices.where(prices > 0)
    rets = np.log(clean / clean.shift(1))

    # Drop days where nothing traded, but keep partial rows. Dropping any row with a NaN
    # would remove most of the data on this market.
    return rets.dropna(how="all")
