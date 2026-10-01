"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Login } from "@/components/Login";
import {
  api, getToken, type Portfolio, type PortfolioSummary, type Position,
} from "@/lib/api";

const fmt = (n: number | null | undefined, d = 2) =>
  n === null || n === undefined ? "—"
    : n.toLocaleString("fr-MA", { minimumFractionDigits: d, maximumFractionDigits: d });

export default function PortfolioPage() {
  const [authed, setAuthed] = useState<boolean | null>(null);
  const [portfolios, setPortfolios] = useState<Portfolio[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [currency, setCurrency] = useState<"MAD" | "USD" | "EUR">("MAD");
  const [fxError, setFxError] = useState<string | null>(null);

  const [ticker, setTicker] = useState("ATW");
  const [qty, setQty] = useState("10");
  const [price, setPrice] = useState("683");
  const [fees, setFees] = useState("10");
  const [busy, setBusy] = useState(false);

  useEffect(() => setAuthed(!!getToken()), []);

  const loadPortfolios = useCallback(async () => {
    const list = await api.portfolios();
    setPortfolios(list);
    if (list.length && !active) setActive(list[0].id);
  }, [active]);

  useEffect(() => { if (authed) loadPortfolios().catch((e) => setError(String(e))); },
            [authed, loadPortfolios]);

  const refresh = useCallback(async (id: string) => {
    setFxError(null);
    const p = await api.positions(id);
    setPositions(p);
    try {
      setSummary(await api.summary(id, currency));
    } catch (e) {
      // No FX rate gives a 503. Show the MAD values and say why, instead of an empty summary.
      if (currency === "MAD") throw e;
      setFxError(`No ${currency} rate available right now — showing MAD.`);
      setSummary(await api.summary(id, "MAD"));
    }
  }, [currency]);

  useEffect(() => { if (active) refresh(active).catch((e) => setError(String(e))); },
            [active, refresh]);

  async function trade(side: "buy" | "sell") {
    if (!active) return;
    setBusy(true);
    setError(null);
    const body = {
      ticker, quantity: Number(qty), price: Number(price), fees: Number(fees || 0),
    };
    try {
      await (side === "buy" ? api.buy(active, body) : api.sell(active, body));
      await refresh(active);
    } catch (e) {
      // Selling too many shares returns a 400 with the reason, show it.
      setError(String(e).includes("INSUFFICIENT_SHARES")
        ? `You do not hold ${qty} shares of ${ticker}.` : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (authed === null) return <div className="empty">…</div>;
  if (!authed) return <Login onSuccess={() => setAuthed(true)} />;

  return (
    <>
      <div className="row" style={{ marginTop: 8, justifyContent: "space-between" }}>
        <h2 style={{ margin: 0 }}>Portfolio</h2>
        <div className="row">
          <select value={active ?? ""} onChange={(e) => setActive(e.target.value)}>
            {portfolios.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <button onClick={async () => {
            const p = await api.createPortfolio(`Portfolio ${portfolios.length + 1}`);
            await loadPortfolios();
            setActive(p.id);
          }}>New</button>
          <div className="seg" title="Display currency for the summary">
            {(["MAD", "USD", "EUR"] as const).map((c) => (
              <button key={c} className={c === currency ? "on" : ""} onClick={() => setCurrency(c)}>{c}</button>
            ))}
          </div>
        </div>
      </div>

      {summary?.fx && (
        <div className="sub muted" style={{ marginTop: 10, fontSize: 12 }}>
          Converted at 1 MAD = {summary.fx.rate} {summary.fx.quote} · rate of {summary.fx.rateDate}
          {" · "}{summary.fx.source === "SYNTHETIC" ? "sample rate" : "Alpha Vantage"}
          {summary.fx.source === "SYNTHETIC" && <span className="badge sample" style={{ marginLeft: 8 }}>Sample data</span>}
          {summary.fx.stale && <span className="badge stale" style={{ marginLeft: 8 }}>Stale rate</span>}
          {" "}— today&apos;s MAD figures at today&apos;s rate, not a foreign investor&apos;s P&amp;L.
          Positions below stay in MAD.
        </div>
      )}
      {fxError && <div className="error" style={{ marginTop: 10 }}>{fxError}</div>}

      {summary && (
        <div className="grid cols-4" style={{ marginTop: 16 }}>
          <div className="card">
            <h3>Market value</h3>
            <div className="value">{fmt(summary.totalValue?.amount)} {summary.totalValue?.currency}</div>
            <div className="sub">{summary.positionCount} positions</div>
          </div>
          <div className="card">
            <h3>Cost basis</h3>
            <div className="value">{fmt(summary.totalCost?.amount)} {summary.totalCost?.currency}</div>
            <div className="sub">fees included</div>
          </div>
          <div className="card">
            <h3>Unrealised P&amp;L</h3>
            <div className={`value ${(summary.unrealizedPnl?.amount ?? 0) >= 0 ? "up" : "down"}`}>
              {fmt(summary.unrealizedPnl?.amount)} {summary.unrealizedPnl?.currency}
            </div>
            <div className={`sub ${summary.unrealizedPnlPercent >= 0 ? "up" : "down"}`}>
              {fmt(summary.unrealizedPnlPercent)}%
            </div>
          </div>
          <div className="card">
            <h3>Concentration (HHI)</h3>
            <div className="value">{summary.herfindahl?.toFixed(3) ?? "—"}</div>
            <div className="sub">
              top-5 {summary.top5Concentration
                ? `${(summary.top5Concentration * 100).toFixed(1)}%` : "—"}
            </div>
          </div>
        </div>
      )}

      <div className="section-title">Record a trade</div>
      <div className="card">
        <div className="row">
          <input value={ticker} onChange={(e) => setTicker(e.target.value.toUpperCase())}
                 placeholder="Ticker" style={{ width: 100 }} />
          <input value={qty} onChange={(e) => setQty(e.target.value)}
                 placeholder="Quantity" type="number" style={{ width: 110 }} />
          <input value={price} onChange={(e) => setPrice(e.target.value)}
                 placeholder="Price" type="number" step="0.01" style={{ width: 110 }} />
          <input value={fees} onChange={(e) => setFees(e.target.value)}
                 placeholder="Fees" type="number" step="0.01" style={{ width: 90 }} />
          <button className="primary" disabled={busy} onClick={() => trade("buy")}>Buy</button>
          <button disabled={busy} onClick={() => trade("sell")}>Sell</button>
        </div>
        {error && <div className="error" style={{ marginTop: 10 }}>{error}</div>}
      </div>

      <div className="section-title">Positions</div>
      <div className="card" style={{ padding: 0, overflowX: "auto" }}>
        <table>
          <thead>
            <tr>
              <th>Ticker</th><th>Qty</th><th>Unit cost</th><th>Last</th>
              <th>Cost basis</th><th>Value</th><th>Unrealised</th><th>%</th><th>Realised</th>
            </tr>
          </thead>
          <tbody>
            {positions.map((p) => (
              <tr key={p.ticker}>
                <td><Link href={`/instrument/${p.ticker}`}><b>{p.ticker}</b></Link></td>
                <td>{fmt(p.quantity, 0)}</td>
                <td className="muted">{fmt(p.unitCost)}</td>
                <td>{fmt(p.lastPrice)}</td>
                <td className="muted">{fmt(p.costBasis)}</td>
                <td>{fmt(p.marketValue)}</td>
                <td className={(p.unrealizedPnl ?? 0) >= 0 ? "up" : "down"}>
                  {fmt(p.unrealizedPnl)}
                </td>
                <td className={(p.unrealizedPnlPercent ?? 0) >= 0 ? "up" : "down"}>
                  {fmt(p.unrealizedPnlPercent)}%
                </td>
                <td className="muted">{fmt(p.realizedPnl)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {positions.length === 0 && <div className="empty">No open positions yet.</div>}
      </div>
    </>
  );
}
