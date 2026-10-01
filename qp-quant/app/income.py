"""
Dividend income projection.

The simple version is amount x yield, but that can be misleading:

  * A high yield is often bad news. Yield = dividend / price, so if the price halves
    before the dividend is cut, the yield doubles. Sorting by yield picks the companies
    most likely to cut (the yield trap). We check for it.
  * The dividend is decided every year and can be cut. The past record (consistency,
    growth, cuts) is the only evidence we have.
  * A payout ratio above 100% means paying more than it earns, which can't last.
  * For a foreign investor the exchange rate can matter more than the yield.

This isn't advice, just projections with their assumptions shown.
"""

from __future__ import annotations

import math
from datetime import date, timedelta

import numpy as np
import pandas as pd

# Fallback rates, only used when neither the caller nor qp-marketdata has one.
# The response always says which source was used.
INDICATIVE_FX = {
    "MAD": 1.0,
    "EUR": 0.092,   # 1 MAD ≈ 0.092 EUR
    "USD": 0.100,   # 1 MAD ≈ 0.100 USD
    "GBP": 0.079,
}
FX_AS_OF = "2026-08 indicative"


def annual_dividends(dividends: list[dict]) -> pd.DataFrame:
    """
    One row per calendar year.

    Most companies here pay once a year, but the date can move into the next year,
    giving one year with two payments and one with none. Summing by year evens that out.
    """
    if not dividends:
        return pd.DataFrame(columns=["year", "amount"])
    df = pd.DataFrame(dividends)
    df["exDate"] = pd.to_datetime(df["exDate"])
    df["year"] = df["exDate"].dt.year
    df["amount"] = pd.to_numeric(df["amount"], errors="coerce")
    grouped = df.groupby("year", as_index=False)["amount"].sum()
    return grouped.sort_values("year").reset_index(drop=True)


def trailing_twelve_months(dividends: list[dict], as_of: date | None = None) -> float:
    """
    Dividends per share declared in the last 365 days.
    We compute it from our own data instead of the vendor's dividendYield,
    which doesn't say if it's trailing, forward or estimated.
    """
    if not dividends:
        return 0.0
    as_of = as_of or date.today()
    cutoff = as_of - timedelta(days=365)
    total = 0.0
    for d in dividends:
        try:
            ex = pd.to_datetime(d["exDate"]).date()
        except Exception:
            continue
        if cutoff <= ex <= as_of:
            total += float(d.get("amount") or 0)
    return total


def growth_analysis(annual: pd.DataFrame) -> dict:
    """
    Consistency and growth of the dividend history.

    CAGR goes from the first to the last year with a payment, so one skipped year
    doesn't make a good payer look like it's shrinking.

    We don't just count cuts. ATW cut 48% in 2020 and was above its old level by 2021:
    one shock and a recovery, not the same as a company that cuts every few years.
    So we report the worst cut, when, and if it recovered.

    A payment moving into the next year looks like a big year then a cut. We flag it
    instead of hiding it.
    """
    if annual.empty or len(annual) < 2:
        return {
            "years": int(len(annual)),
            "cagr": None,
            "cuts": 0,
            "skippedYears": 0,
            "consistency": None,
            "verdict": "insufficient history to judge consistency",
            "series": [
                {"year": int(r.year), "amount": round(float(r.amount), 4)}
                for r in annual.itertuples()
            ],
        }

    amounts = [float(a) for a in annual["amount"].values]
    years = [int(y) for y in annual["year"].values]

    cut_events = []
    for i in range(1, len(amounts)):
        if amounts[i] < amounts[i - 1] * 0.99:
            drop = (amounts[i - 1] - amounts[i]) / amounts[i - 1]
            cut_events.append({
                "year": years[i],
                "from": round(amounts[i - 1], 4),
                "to": round(amounts[i], 4),
                "dropPct": round(drop, 4),
            })

    span = years[-1] - years[0]
    covered = span + 1
    skipped = int(covered - len(annual))

    first, last = amounts[0], amounts[-1]
    cagr = (last / first) ** (1 / span) - 1 if span > 0 and first > 0 and last > 0 else None

    consistency = len(annual) / covered if covered else None

    worst = max(cut_events, key=lambda c: c["dropPct"]) if cut_events else None
    peak = max(amounts)
    recovered = bool(worst and last >= peak * 0.99)

    # Relative to the history length: 2 cuts in 15 years isn't 2 cuts in 4.
    cuts_per_decade = (len(cut_events) / covered * 10) if covered else 0

    if not cut_events and skipped == 0:
        verdict = f"paid every year for {covered} years and never reduced — a strong record"
        quality = "strong"
    elif worst and len(cut_events) <= 2 and recovered:
        verdict = (
            f"cut {worst['dropPct']:.0%} in {worst['year']} but has since recovered to a "
            f"new high — consistent with a one-off shock rather than chronic weakness"
        )
        quality = "resilient"
    elif cuts_per_decade <= 1.5 and skipped == 0:
        verdict = (
            f"paid every year for {covered} years with {len(cut_events)} reduction(s) — "
            "broadly reliable"
        )
        quality = "reliable"
    elif skipped > 0 and not cut_events:
        verdict = f"{skipped} year(s) with no payment, but never reduced when paid"
        quality = "intermittent"
    else:
        verdict = (
            f"{len(cut_events)} reduction(s) and {skipped} skipped year(s) across "
            f"{covered} years — an inconsistent record"
        )
        quality = "weak"

    # A payment moving into the next year looks like a spike then a cut.
    catch_up_suspected = bool(
        worst and any(
            amounts[i] > amounts[i - 1] * 1.5
            for i in range(1, len(amounts))
        )
    )

    return {
        "years": int(len(annual)),
        "spanYears": covered,
        "cagr": round(cagr, 6) if cagr is not None else None,
        "cuts": len(cut_events),
        "cutEvents": cut_events,
        "worstCut": worst,
        "recoveredToHigh": recovered,
        "skippedYears": skipped,
        "consistency": round(consistency, 4) if consistency else None,
        "quality": quality,
        "verdict": verdict,
        "dataCaveat": (
            "One year shows a jump of more than 50%, which on this market usually means a "
            "delayed payment landed in the following calendar year rather than a genuine "
            "doubling. The apparent cut in the year after is likely an artefact of grouping "
            "by calendar year."
            if catch_up_suspected else None
        ),
        "series": [
            {"year": int(y), "amount": round(a, 4)} for y, a in zip(years, amounts)
        ],
    }


def payout_ratio(dps: float, price: float, pe_ratio: float | None) -> dict:
    """
    Payout ratio from what we have.

    The feed has no EPS, but EPS = price / PE, so:

        payout = DPS / EPS = DPS x PE / price

    Above 100%: pays more than it earns. 70-100%: little room for a bad year.
    Under ~40%: well covered.

    If PE is missing we return available: False instead of guessing.
    """
    if not pe_ratio or pe_ratio <= 0 or price <= 0 or dps <= 0:
        return {"available": False,
                "reason": "needs a positive P/E, price and dividend"}

    eps = price / pe_ratio
    ratio = dps / eps if eps > 0 else None
    if ratio is None:
        return {"available": False, "reason": "could not derive EPS"}

    if ratio > 1.0:
        verdict = ("paying out more than it earns — not sustainable from earnings alone")
        risk = "high"
    elif ratio > 0.7:
        verdict = "little headroom; a weak year would pressure the dividend"
        risk = "elevated"
    elif ratio > 0.4:
        verdict = "comfortably covered by earnings"
        risk = "moderate"
    else:
        verdict = "well covered, with room to grow the dividend"
        risk = "low"

    return {
        "available": True,
        "payoutRatio": round(ratio, 4),
        "impliedEps": round(eps, 4),
        "risk": risk,
        "verdict": verdict,
        "method": "EPS derived as price / P/E; payout = DPS / EPS",
    }


def yield_trap_check(
    current_yield: float,
    growth: dict,
    price_change_1y: float | None,
    payout: dict,
    market_median_yield: float | None = None,
) -> dict:
    """
    Flags when a high yield is a warning sign rather than a good thing.

    Four signals, none is enough alone:

      1. yield well above the market median
      2. price has dropped a lot (usually why the yield is high)
      3. the dividend was cut before
      4. payout above 100%

    Two or more together often means the market expects a cut.
    """
    signals = []

    if market_median_yield and current_yield > market_median_yield * 1.8:
        signals.append(
            f"yield of {current_yield:.2%} is far above the market median of "
            f"{market_median_yield:.2%}")

    if price_change_1y is not None and price_change_1y < -0.15:
        signals.append(
            f"price fell {abs(price_change_1y):.1%} over the year, which is what "
            "mechanically raised the yield")

    # One cut followed by recovery isn't the same as cutting often, so no flag.
    if growth.get("cuts", 0) > 0 and growth.get("quality") not in ("resilient", "reliable"):
        worst = growth.get("worstCut")
        detail = (f" (worst: {worst['dropPct']:.0%} in {worst['year']})" if worst else "")
        signals.append(
            f"the dividend has been reduced {growth['cuts']} time(s){detail}")

    if payout.get("available") and payout.get("payoutRatio", 0) > 1.0:
        signals.append(
            f"payout ratio of {payout['payoutRatio']:.0%} exceeds earnings")

    if len(signals) >= 2:
        level = "high"
        summary = ("Several yield-trap signals present. A high yield here more likely "
                   "reflects a falling price than a generous dividend.")
    elif len(signals) == 1:
        level = "moderate"
        summary = "One warning signal. Worth checking why the yield is elevated."
    else:
        level = "low"
        summary = "No yield-trap signals in the available data."

    return {"level": level, "signals": signals, "summary": summary}


def project_income(
    amount: float,
    currency: str,
    instrument: dict,
    dividends: list[dict],
    price_change_1y: float | None = None,
    fx_rate: float | None = None,
    fx_source: str | None = None,
    horizon_years: int = 5,
    market_median_yield: float | None = None,
) -> dict:
    """
    Projects dividend income for an investment.

    amount is in currency; the stock is in MAD, so we convert at fx_rate before
    counting the shares.

    Shares are rounded down: the exchange doesn't trade fractions, and keeping them
    would overstate the income.
    """
    price = float(instrument.get("price") or 0)
    if price <= 0:
        return {"available": False, "reason": "no current price for this instrument"}

    currency = currency.upper()
    if fx_rate is None:
        fx_rate = INDICATIVE_FX.get(currency)
        fx_source = f"indicative ({FX_AS_OF})"
    elif fx_source is None:
        fx_source = "caller-supplied"
    if not fx_rate or fx_rate <= 0:
        return {"available": False, "reason": f"no FX rate available for {currency}"}

    amount_mad = amount / fx_rate
    shares = math.floor(amount_mad / price)
    if shares < 1:
        return {
            "available": False,
            "reason": (f"{amount:,.0f} {currency} converts to {amount_mad:,.0f} MAD, "
                       f"below the {price:,.2f} MAD price of one share"),
        }

    invested_mad = shares * price
    uninvested_mad = amount_mad - invested_mad

    annual = annual_dividends(dividends)
    ttm = trailing_twelve_months(dividends)
    growth = growth_analysis(annual)

    # Yield from our own data, not the vendor's field.
    current_yield = ttm / price if price > 0 else 0.0
    payout = payout_ratio(ttm, price, instrument.get("peRatio"))
    trap = yield_trap_check(current_yield, growth, price_change_1y, payout,
                            market_median_yield)

    income_mad_year1 = shares * ttm
    cagr = growth.get("cagr")

    # Three scenarios instead of one number, the inputs aren't precise enough for one.
    scenarios = {}
    for name, rate, note in [
        ("flat", 0.0, "dividend never changes from its trailing level"),
        ("historical",
         cagr if cagr is not None else 0.0,
         f"dividend grows at its own historical CAGR of {cagr:.2%}"
         if cagr is not None else "no growth history available; treated as flat"),
        ("cut", -0.30, "a one-off 30% cut in year 1, flat thereafter"),
    ]:
        stream = []
        total = 0.0
        for year in range(1, horizon_years + 1):
            if name == "cut":
                d = ttm * 0.7
            else:
                d = ttm * ((1 + rate) ** (year - 1))
            annual_income = shares * d
            total += annual_income
            stream.append({
                "year": year,
                "dividendPerShare": round(d, 4),
                "incomeMad": round(annual_income, 2),
                "incomeTarget": round(annual_income * fx_rate, 2),
            })
        scenarios[name] = {
            "assumption": note,
            "annual": stream,
            "cumulativeMad": round(total, 2),
            "cumulativeTarget": round(total * fx_rate, 2),
            # Simple, not compounded: income vs money invested. Reinvesting is a separate choice.
            "yieldOnCostTotal": round(total / invested_mad, 4) if invested_mad else None,
        }

    return {
        "available": True,
        "ticker": instrument.get("ticker"),
        "name": instrument.get("name"),
        "sector": instrument.get("sector"),
        "investment": {
            "amount": round(amount, 2),
            "currency": currency,
            "fxRate": fx_rate,
            "fxSource": fx_source,
            "amountMad": round(amount_mad, 2),
            "sharePriceMad": round(price, 2),
            "shares": shares,
            "investedMad": round(invested_mad, 2),
            "uninvestedMad": round(uninvested_mad, 2),
        },
        "dividend": {
            "trailingTwelveMonthsPerShare": round(ttm, 4),
            "currentYield": round(current_yield, 6),
            "year1IncomeMad": round(income_mad_year1, 2),
            "year1IncomeTarget": round(income_mad_year1 * fx_rate, 2),
            "monthlyEquivalentTarget": round(income_mad_year1 * fx_rate / 12, 2),
        },
        "history": growth,
        "sustainability": payout,
        "yieldTrap": trap,
        "scenarios": scenarios,
        "risks": _risk_notes(currency, current_yield, trap, growth, instrument),
        "disclaimer": (
            "Projection under stated assumptions from public, delayed data. Dividends are "
            "declared by a board each year and can be reduced or suspended. This is "
            "informational analysis, not investment advice."
        ),
    }


def _risk_notes(currency, current_yield, trap, growth, instrument) -> list[str]:
    notes = []

    if currency != "MAD":
        notes.append(
            f"**FX risk.** Dividends are paid in MAD and converted to {currency}. The "
            f"projected yield of {current_yield:.2%} is smaller than a typical annual "
            f"move in MAD/{currency}, so exchange rates can easily outweigh the income. "
            "The dirham is a managed float against a EUR/USD basket, which limits but "
            "does not remove this."
        )

    if trap["level"] in ("high", "moderate"):
        notes.append(f"**Yield-trap risk ({trap['level']}).** {trap['summary']}")

    worst = growth.get("worstCut")
    if worst:
        recovered = " It has since recovered to a new high." if growth.get("recoveredToHigh") else ""
        notes.append(
            f"**Cut history.** The largest reduction was {worst['dropPct']:.0%} in "
            f"{worst['year']} ({worst['from']} to {worst['to']} per share).{recovered} "
            "The trailing dividend is not a floor."
        )

    if growth.get("years", 0) < 5:
        notes.append(
            f"**Short record.** Only {growth.get('years', 0)} year(s) of dividend history, "
            "which is too little to establish a pattern."
        )

    notes.append(
        "**Concentration.** This projects a single holding. Income from one issuer depends "
        "entirely on that issuer's board."
    )
    notes.append(
        "**Price risk is not modelled here.** A 4% dividend does not protect against a 20% "
        "fall in the share price; the two are separate questions."
    )
    return notes


def screen_income(rows: list[dict]) -> dict:
    """
    Ranks stocks by yield, with their quality signals.

    Sorted by yield, but each row shows consistency, cuts and trap level, because the
    highest yields are often the companies about to cut.
    """
    scored = [r for r in rows if r.get("currentYield", 0) > 0]
    if not scored:
        return {"available": False, "reason": "no instruments with dividend history"}

    yields = sorted(r["currentYield"] for r in scored)
    median = float(np.median(yields))

    return {
        "available": True,
        "count": len(scored),
        "medianYield": round(median, 6),
        "instruments": sorted(scored, key=lambda r: -r["currentYield"]),
        "note": (
            "Ordered by trailing yield, but read the consistency and trap columns before "
            "the yield column. The highest-yielding names in any market are "
            "disproportionately those whose price has fallen in anticipation of a cut."
        ),
    }
