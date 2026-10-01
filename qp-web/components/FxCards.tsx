"use client";

import { useEffect, useState } from "react";
import { api, type FxQuote } from "@/lib/api";

const fmtRate = (n: number) =>
  n.toLocaleString("fr-MA", { minimumFractionDigits: 4, maximumFractionDigits: 4 });

/** One card per dirham rate, with change, date and source. */
export function FxCards() {
  const [quotes, setQuotes] = useState<FxQuote[] | null>(null);

  useEffect(() => {
    api.fxRates("USD-MAD,EUR-MAD").then(setQuotes).catch(() => setQuotes([]));
  }, []);

  const pairs = ["USD", "EUR"];
  return (
    <>
      {pairs.map((base) => {
        const q = quotes?.find((x) => x.base === base);
        return (
          <div className="card" key={base}>
            <div className="row" style={{ justifyContent: "space-between", gap: 6 }}>
              <h3 style={{ margin: 0 }}>{base}/MAD</h3>
              {q?.source === "SYNTHETIC" && <span className="badge sample" title="Synthetic fixture until recorded with a real Alpha Vantage key">Sample</span>}
              {q?.stale && <span className="badge stale" title={`${q.ageDays} days old`}>Stale</span>}
            </div>
            {!quotes ? (
              <div className="sub">Loading…</div>
            ) : !q ? (
              <div className="sub">No rate stored yet</div>
            ) : (
              <>
                <div className="value">{fmtRate(q.rate)}</div>
                <div className={`sub ${(q.changePercent ?? 0) >= 0 ? "up" : "down"}`}>
                  {q.changePercent == null ? "—" : `${q.changePercent >= 0 ? "▲" : "▼"} ${Math.abs(q.changePercent).toFixed(2)}%`}
                  <span className="muted"> · {q.rateDate} · {q.source === "SYNTHETIC" ? "sample" : "Alpha Vantage"}</span>
                </div>
              </>
            )}
          </div>
        );
      })}
    </>
  );
}
