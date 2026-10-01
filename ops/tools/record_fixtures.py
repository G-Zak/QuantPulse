#!/usr/bin/env python3
"""
Records real Drahmi API responses to ops/fixtures/ so dev and tests
don't use the 100 requests/day quota.

This is the only script that calls the live API. Tests and the dev profile
read the saved JSON files instead.

  - files that already exist are skipped, so re-running costs nothing
  - stops if X-RateLimit-Remaining goes below --floor
  - --dry-run shows the plan without calling

Usage:
    python3 ops/tools/record_fixtures.py --dry-run
    python3 ops/tools/record_fixtures.py
    python3 ops/tools/record_fixtures.py --only technicals
"""

from __future__ import annotations

import argparse
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = REPO_ROOT / "ops" / "fixtures"
MANIFEST = FIXTURE_DIR / "_manifest.json"

# Cloudflare blocks the default urllib User-Agent.
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# Three liquid tickers from different sectors are enough to cover every endpoint.
TICKERS = ["ATW", "IAM", "MNG"]


def ssl_context() -> ssl.SSLContext:
    """
    Python from python.org on macOS has no CA bundle set up, so HTTPS fails.
    Use certifi if installed, otherwise the system bundle. Never turn verification off.
    """
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        pass
    if Path("/etc/ssl/cert.pem").exists():
        return ssl.create_default_context(cafile="/etc/ssl/cert.pem")
    return ssl.create_default_context()


def plan() -> list[tuple[str, str, str, dict | None]]:
    """Return [(slug, method, path, json_body)] in recording order."""
    calls: list[tuple[str, str, str, dict | None]] = [
        # Market-wide endpoints, one call each
        ("market-overview",      "GET", "/market/overview", None),
        ("market-status",        "GET", "/market/status", None),
        # All 81 instruments in one call.
        ("stocks-all",           "GET", "/stocks?limit=200", None),
        ("sectors",              "GET", "/sectors", None),
        ("indices",              "GET", "/indices", None),
        ("index-MASI",           "GET", "/indices/MASI", None),
        ("index-MASI-history-1Y", "GET", "/indices/MASI/history?range=1Y", None),
        # MASI20 history, used as the benchmark for beta/alpha.
        ("index-MASI20-history-1Y", "GET", "/indices/MASI20/history?range=1Y", None),
        ("market-gainers",       "GET", "/market/gainers?limit=20", None),
        ("market-losers",        "GET", "/market/losers?limit=20", None),
        ("market-most-active",   "GET", "/market/most-active?limit=20", None),
        ("news",                 "GET", "/news?limit=20", None),
        ("search-bank",          "GET", "/search?q=bank&limit=20", None),
    ]

    for t in TICKERS:
        calls += [
            (f"stock-{t}",             "GET", f"/stocks/{t}", None),
            # 1 year of daily bars for the risk calculations.
            (f"stock-{t}-history-1Y",  "GET", f"/stocks/{t}/history?range=1Y", None),
            (f"technicals-{t}-6M",     "GET",
             f"/intelligence/stocks/{t}/technicals?range=6M&includeMetadata=true", None),
            # Drahmi's own values, to check our calculations against.
            (f"risk-{t}-6M",           "GET",
             f"/intelligence/stocks/{t}/risk?range=6M&benchmark=MASI&includeMetadata=true", None),
        ]

    # Detailed endpoints, one ticker only. We only need the response format.
    calls += [
        ("dividends-ATW",       "GET", "/stocks/ATW/dividends", None),
        ("ownership-ATW",       "GET", "/stocks/ATW/ownership?page=1&pageSize=10", None),
        ("sharia-ATW",          "GET", "/stocks/ATW/sharia-compliance?methodology=COMMON_INDEX", None),
        ("market-analysis-ATW", "GET", "/stocks/ATW/market-analysis?benchmark=MASI20&period=1Y", None),
        ("fundamentals-ATW",    "GET", "/stocks/ATW/fundamentals?statementType=INCOME&periodType=ANNUAL", None),
        ("financials-ATW",      "GET", "/stocks/ATW/financials/summary", None),
        ("liquidity-ATW-3M",    "GET", "/intelligence/stocks/ATW/liquidity?range=3M", None),
        ("signals-ATW-3M",      "GET", "/intelligence/stocks/ATW/signals?range=3M", None),
        ("performance-ATW-1Y",  "GET",
         "/intelligence/stocks/ATW/performance?range=1Y&benchmark=MASI", None),
        ("portfolio-list",      "GET", "/portfolio", None),
        ("portfolio-summary-calc", "POST", "/intelligence/portfolio/summary",
         {"holdings": [{"ticker": "ATW", "quantity": 100},
                       {"ticker": "IAM", "quantity": 250},
                       {"ticker": "MNG", "quantity": 40}]}),
    ]
    return calls


def call(base: str, key: str, method: str, path: str, body: dict | None):
    url = base.rstrip("/") + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("X-API-Key", key)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(req, timeout=30, context=ssl_context()) as resp:
            payload = resp.read().decode()
            headers = dict(resp.headers)
            return resp.status, payload, headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), dict(e.headers)


def remaining_of(headers: dict) -> int | None:
    for k, v in headers.items():
        if k.lower() == "x-ratelimit-remaining":
            try:
                return int(v)
            except ValueError:
                return None
    return None


def load_env() -> tuple[str, str]:
    env_path = REPO_ROOT / ".env"
    values: dict[str, str] = {}
    if env_path.exists():
        for line in env_path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                values[k.strip()] = v.strip()
    key = os.environ.get("DRAHMI_API_KEY") or values.get("DRAHMI_API_KEY", "")
    base = os.environ.get("DRAHMI_BASE_URL") or values.get(
        "DRAHMI_BASE_URL", "https://api.drahmi.app/api/v1")
    if not key or key.endswith("xxxx"):
        sys.exit("DRAHMI_API_KEY not set. Copy .env.example to .env and fill it in.")
    return base, key


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--only", default="", help="only record slugs containing this substring")
    ap.add_argument("--floor", type=int, default=15,
                    help="abort when X-RateLimit-Remaining drops below this")
    ap.add_argument("--delay", type=float, default=0.4, help="seconds between calls")
    args = ap.parse_args()

    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    calls = [c for c in plan() if args.only in c[0]]
    todo = [c for c in calls if not (FIXTURE_DIR / f"{c[0]}.json").exists()]

    print(f"planned={len(calls)}  already recorded={len(calls) - len(todo)}  "
          f"will spend={len(todo)} call(s)")
    if args.dry_run:
        for slug, method, path, _ in todo:
            print(f"  {method:4} {path}   -> {slug}.json")
        return
    if not todo:
        print("nothing to do — all fixtures present")
        return

    base, key = load_env()
    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    recorded = failed = 0
    remaining = None

    for slug, method, path, body in todo:
        status, payload, headers = call(base, key, method, path, body)
        remaining = remaining_of(headers) if remaining_of(headers) is not None else remaining

        # This endpoint returns 201, so accept any 2xx.
        if not (200 <= status < 300):
            print(f"  ✗ {status} {method} {path}  {payload[:120]}")
            failed += 1
            if status == 429:
                print("  !! rate limited — stopping")
                break
            continue

        try:
            parsed = json.loads(payload)
        except json.JSONDecodeError:
            print(f"  ✗ non-JSON body for {path}")
            failed += 1
            continue

        (FIXTURE_DIR / f"{slug}.json").write_text(
            json.dumps(parsed, indent=2, ensure_ascii=False) + "\n")
        manifest[slug] = {
            "method": method,
            "path": path,
            "body": body,
            "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "asOf": headers.get("X-Drahmi-As-Of"),
            "dataDelay": headers.get("X-Drahmi-Data-Delay"),
            "qualityScore": headers.get("X-Drahmi-Quality-Score"),
        }
        recorded += 1
        size = len(payload)
        print(f"  ✓ {slug:26} {size:>7}B   remaining={remaining}")

        if remaining is not None and remaining < args.floor:
            print(f"  !! quota floor reached (remaining={remaining} < {args.floor}) — stopping")
            break
        time.sleep(args.delay)

    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"\nrecorded={recorded}  failed={failed}  quota remaining={remaining}")


if __name__ == "__main__":
    main()
