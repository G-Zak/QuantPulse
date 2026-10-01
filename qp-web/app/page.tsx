"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { api, subscribePrices, type Dashboard, type Instrument, type Quota } from "@/lib/api";
import { BriefCard } from "@/components/BriefCard";
import { FxCards } from "@/components/FxCards";

const fmt = (n: number | null | undefined, d = 2) =>
  n === null || n === undefined ? "—" : n.toLocaleString("fr-MA", {
    minimumFractionDigits: d, maximumFractionDigits: d,
  });

const fmtCap = (n: number | null) => {
  if (!n) return "—";
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)} Md`;
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)} M`;
  return fmt(n, 0);
};

export default function MarketPage() {
  const [data, setData] = useState<Dashboard | null>(null);
  const [quota, setQuota] = useState<Quota | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [live, setLive] = useState<Record<string, number>>({});
  const [flash, setFlash] = useState<Record<string, "up" | "down">>({});
  const prevPrices = useRef<Record<string, number>>({});

  useEffect(() => {
    api.dashboard().then(setData).catch((e) => setError(String(e)));
    api.quota().then(setQuota).catch(() => {});
  }, []);

  // Live prices over SSE. No filter means the whole market.
  useEffect(() => {
    return subscribePrices([], (tick) => {
      const prev = prevPrices.current[tick.ticker];
      prevPrices.current[tick.ticker] = tick.price;
      setLive((s) => ({ ...s, [tick.ticker]: tick.price }));
      if (prev !== undefined && prev !== tick.price) {
        setFlash((f) => ({ ...f, [tick.ticker]: tick.price > prev ? "up" : "down" }));
        setTimeout(() => setFlash((f) => {
          const next = { ...f }; delete next[tick.ticker]; return next;
        }), 900);
      }
    });
  }, []);

  const rows = useMemo(() => {
    if (!data) return [];
    const q = query.trim().toUpperCase();
    const list = q
      ? data.instruments.filter(
          (i) => i.ticker.includes(q) || i.name.toUpperCase().includes(q))
      : data.instruments;
    return list;
  }, [data, query]);

  if (error) return <div className="error" style={{ marginTop: 40 }}>Could not load: {error}</div>;
  if (!data) return <div className="empty">Loading market…</div>;

  const { overview, indices } = data;

  return (
    <>
      <div className="grid cols-4" style={{ marginTop: 8 }}>
        {indices.map((idx) => (
          <div className="card" key={idx.code}>
            <h3>{idx.code}</h3>
            <div className="value">{fmt(idx.value)}</div>
            <div className={`sub ${(idx.changePercent ?? 0) >= 0 ? "up" : "down"}`}>
              {(idx.changePercent ?? 0) >= 0 ? "▲" : "▼"} {fmt(idx.changePercent)}%
              &nbsp;({fmt(idx.changeValue)})
            </div>
          </div>
        ))}
        <div className="card">
          <h3>Listed</h3>
          <div className="value">{overview.instrumentCount}</div>
          <div className="sub">
            <span className={`dot ${overview.marketOpen ? "open" : "closed"}`} />
            {overview.marketOpen ? "Session open" : "Closed"} · {overview.session}
          </div>
        </div>
        <div className="card">
          <h3>API quota today</h3>
          <div className="value">
            {quota ? `${quota.remaining}/${quota.dailyLimit}` : "—"}
          </div>
          <div className="sub">
            {quota ? `${quota.consumed} spent · resets 00:00 UTC` : "—"}
          </div>
        </div>
      </div>

      <div className="grid cols-4" style={{ marginTop: 16 }}>
        <BriefCard />
        <FxCards />
      </div>

      <div className="row" style={{ marginTop: 28, justifyContent: "space-between" }}>
        <div className="section-title" style={{ margin: 0 }}>
          Actions cotées <span className="tag">{rows.length}</span>
        </div>
        <input value={query} onChange={(e) => setQuery(e.target.value)}
               placeholder="Search ticker or name…" style={{ width: 260 }} />
      </div>

      <div className="card" style={{ padding: 0, overflowX: "auto" }}>
        <table>
          <thead>
            <tr>
              <th>Ticker</th><th style={{ textAlign: "left" }}>Name</th>
              <th style={{ textAlign: "left" }}>Sector</th>
              <th>Price</th><th>Chg %</th><th>Market cap</th><th>Div. yield</th><th>P/E</th>
              <th>52w low</th><th>52w high</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((i: Instrument) => {
              const price = live[i.ticker] ?? i.price;
              return (
                <tr key={i.ticker} className={flash[i.ticker] ? `flash-${flash[i.ticker]}` : ""}>
                  <td><Link href={`/instrument/${i.ticker}`}><b>{i.ticker}</b></Link></td>
                  <td style={{ textAlign: "left" }}>
                    <Link href={`/instrument/${i.ticker}`}>{i.name}</Link>
                  </td>
                  <td style={{ textAlign: "left" }}>
                    <span className="tag">{i.sector ?? "—"}</span>
                  </td>
                  <td><b>{fmt(price)}</b></td>
                  <td className={i.changePercent == null ? "muted" : i.changePercent > 0 ? "up" : i.changePercent < 0 ? "down" : "muted"}>
                    {i.changePercent == null ? "—" : `${i.changePercent > 0 ? "+" : ""}${fmt(i.changePercent)}%`}
                  </td>
                  <td className="muted">{fmtCap(i.marketCap)}</td>
                  <td className="muted">{i.dividendYield ? `${fmt(i.dividendYield)}%` : "—"}</td>
                  <td className="muted">{fmt(i.peRatio)}</td>
                  <td className="muted">{fmt(i.week52Low)}</td>
                  <td className="muted">{fmt(i.week52High)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {rows.length === 0 && <div className="empty">No instruments match “{query}”.</div>}
      </div>

      <p className="muted" style={{ fontSize: 12, marginTop: 20 }}>
        Delayed data from Drahmi, dirham rates from Alpha Vantage, both served from
        QuantPulse&apos;s own store — a page refresh costs zero upstream API calls. Prices
        update live over SSE.
      </p>
    </>
  );
}
