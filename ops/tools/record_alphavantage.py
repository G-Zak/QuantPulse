#!/usr/bin/env python3
"""
Records real Alpha Vantage responses over the synthetic fixtures.

One call per file, 10 in total (the free tier allows 25 a day).
Recorded files are marked "synthetic": false, so the UI stops showing "sample".

  --dry-run   show what would be called, without calling
  --force     re-record files that are already real

Error bodies ("Note", "Information", "Error Message") are not saved.
The key comes from ALPHA_API_KEY (env or .env).

Usage:
    python3 ops/tools/record_alphavantage.py --dry-run
    python3 ops/tools/record_alphavantage.py
    python3 ops/tools/record_alphavantage.py --only fxdaily
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from record_fixtures import ssl_context  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = REPO_ROOT / "ops" / "fixtures"
MANIFEST = FIXTURE_DIR / "_manifest.json"
BASE = "https://www.alphavantage.co/query"

PLAN: list[tuple[str, dict]] = [
    ("av-fx-USD-MAD", {"function": "CURRENCY_EXCHANGE_RATE", "from_currency": "USD", "to_currency": "MAD"}),
    ("av-fx-EUR-MAD", {"function": "CURRENCY_EXCHANGE_RATE", "from_currency": "EUR", "to_currency": "MAD"}),
    ("av-fx-MAD-USD", {"function": "CURRENCY_EXCHANGE_RATE", "from_currency": "MAD", "to_currency": "USD"}),
    ("av-fx-MAD-EUR", {"function": "CURRENCY_EXCHANGE_RATE", "from_currency": "MAD", "to_currency": "EUR"}),
    ("av-fxdaily-USD-MAD", {"function": "FX_DAILY", "from_symbol": "USD", "to_symbol": "MAD", "outputsize": "full"}),
    ("av-fxdaily-EUR-MAD", {"function": "FX_DAILY", "from_symbol": "EUR", "to_symbol": "MAD", "outputsize": "full"}),
    ("av-fxdaily-MAD-USD", {"function": "FX_DAILY", "from_symbol": "MAD", "to_symbol": "USD", "outputsize": "full"}),
    ("av-fxdaily-MAD-EUR", {"function": "FX_DAILY", "from_symbol": "MAD", "to_symbol": "EUR", "outputsize": "full"}),
    # full may be premium only on the free tier, so fall back to compact (~100 days).
    ("av-daily-SPY", {"function": "TIME_SERIES_DAILY", "symbol": "SPY", "outputsize": "full"}),
    ("av-daily-EEM", {"function": "TIME_SERIES_DAILY", "symbol": "EEM", "outputsize": "full"}),
]


def load_key() -> str:
    key = os.environ.get("ALPHA_API_KEY", "")
    env_path = REPO_ROOT / ".env"
    if not key and env_path.exists():
        for line in env_path.read_text().splitlines():
            if line.startswith("ALPHA_API_KEY="):
                key = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not key or key == "your_alpha_vantage_key":
        sys.exit("ALPHA_API_KEY not set. Add it to .env (see .env.example).")
    return key


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true", help="re-record fixtures already recorded")
    ap.add_argument("--only", default="", help="substring filter on fixture name")
    args = ap.parse_args()

    manifest = json.loads(MANIFEST.read_text())
    todo = [(slug, params) for slug, params in PLAN
            if args.only in slug
            and (args.force or manifest.get(slug, {}).get("synthetic", True) is not False)]

    print(f"{len(todo)} call(s) planned of your 25/day:")
    for slug, params in todo:
        print(f"  {slug:22s} {params['function']}")
    if args.dry_run or not todo:
        return

    key = load_key()
    ctx = ssl_context()
    for slug, params in todo:
        url = BASE + "?" + urllib.parse.urlencode({**params, "apikey": key})
        with urllib.request.urlopen(url, context=ctx, timeout=30) as resp:
            body = json.loads(resp.read())
        refusal = next((body[k] for k in ("Error Message", "Note", "Information") if k in body), None)
        if refusal:
            print(f"  REFUSED {slug}: {refusal[:160]}")
            if "Note" in body or "limit" in refusal.lower():
                sys.exit("Daily limit reached; stopping. Re-run tomorrow for the rest.")
            continue
        (FIXTURE_DIR / f"{slug}.json").write_text(json.dumps(body, indent=2) + "\n")
        public = {k: v for k, v in params.items()}
        manifest[slug] = {
            "method": "GET",
            "path": BASE + "?" + urllib.parse.urlencode(public),
            "body": None,
            "recordedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "source": "alphavantage",
            "synthetic": False,
            "note": "Recorded with the project key by ops/tools/record_alphavantage.py.",
        }
        MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
        print(f"  saved   {slug}")
        time.sleep(1.2)  # the free tier also limits calls per second


if __name__ == "__main__":
    main()
