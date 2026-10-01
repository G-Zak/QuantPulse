"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from "recharts";
import { api, type InstrumentDetail } from "@/lib/api";

const RANGES = ["1M", "3M", "6M", "1Y"];

const pct = (n: number | null) => (n === null ? "—" : `${(n * 100).toFixed(2)}%`);
const num = (n: number | null, d = 3) => (n === null ? "—" : n.toFixed(d));

export default function InstrumentPage({ params }: { params: Promise<{ ticker: string }> }) {
  const { ticker } = use(params);
  const [range, setRange] = useState("6M");
  const [data, setData] = useState<InstrumentDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setData(null);
    api.instrumentDetail(ticker, range).then(setData).catch((e) => setError(String(e)));
  }, [ticker, range]);

  if (error) return <div className="error">Could not load {ticker}: {error}</div>;
  if (!data) return <div className="empty">Loading {ticker}…</div>;

  const { instrument, history, risk } = data;
  const chart = history.map((b) => ({ date: b.date, close: b.close, volume: b.volume }));
  const first = chart[0]?.close ?? 0;
  const last = chart[chart.length - 1]?.close ?? 0;
  const up = last >= first;

  return (
    <>
      <div className="row" style={{ marginTop: 8, justifyContent: "space-between" }}>
        <div>
          <Link href="/" className="muted">← Market</Link>
          <h2 style={{ margin: "8px 0 2px" }}>
            {instrument.ticker} <span className="muted" style={{ fontWeight: 400 }}>
              {instrument.name}</span>
          </h2>
          <span className="tag">{instrument.sector ?? "—"}</span>
        </div>
        <div className="row">
          {RANGES.map((r) => (
            <button key={r} className={r === range ? "primary" : ""} onClick={() => setRange(r)}>
              {r}
            </button>
          ))}
        </div>
      </div>

      <div className="card" style={{ marginTop: 16 }}>
        <div className="row" style={{ justifyContent: "space-between", marginBottom: 8 }}>
          <div>
            <div className="value" style={{ fontSize: 28 }}>
              {instrument.price?.toFixed(2) ?? "—"} <span className="muted"
                style={{ fontSize: 14 }}>{instrument.currency}</span>
            </div>
            <div className={`sub ${up ? "up" : "down"}`}>
              {up ? "▲" : "▼"} {pct(risk.totalReturn)} over {range}
            </div>
          </div>
          <div className="muted" style={{ fontSize: 12, textAlign: "right" }}>
            {risk.sessions} sessions · {risk.zeroVolumeDays} with zero volume
          </div>
        </div>
        <div style={{ height: 300 }}>
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chart} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={up ? "#26a37b" : "#e0524c"} stopOpacity={0.35} />
                  <stop offset="100%" stopColor={up ? "#26a37b" : "#e0524c"} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#232c3d" vertical={false} />
              <XAxis dataKey="date" tick={{ fontSize: 11, fill: "#8b98ad" }}
                     minTickGap={40} tickLine={false} axisLine={false} />
              <YAxis domain={["auto", "auto"]} tick={{ fontSize: 11, fill: "#8b98ad" }}
                     tickLine={false} axisLine={false} width={54} />
              <Tooltip
                contentStyle={{ background: "#131822", border: "1px solid #232c3d",
                                borderRadius: 8, fontSize: 12 }}
                labelStyle={{ color: "#8b98ad" }} />
              <Area type="monotone" dataKey="close" stroke={up ? "#26a37b" : "#e0524c"}
                    strokeWidth={2} fill="url(#g)" />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="section-title">
        Risk metrics
        <span className="tag" style={{ marginLeft: 8, textTransform: "none" }}>
          computed locally from stored OHLCV
        </span>
      </div>
      <div className="grid cols-4">
        <Metric label="Volatility (ann.)" value={pct(risk.realizedVolAnnualized)}
                note="σ of log returns × √252" />
        <Metric label="Downside vol" value={pct(risk.downsideVolAnnualized)}
                note="negatives only, ÷ total n" />
        <Metric label="Max drawdown" value={pct(risk.maxDrawdown)}
                note="worst peak-to-trough" />
        <Metric label="Beta vs MASI" value={num(risk.beta)}
                note="date-aligned regression" />
        <Metric label="VaR 95%" value={pct(risk.var95)}
                note="historical, not parametric" />
        <Metric label="CVaR 95%" value={pct(risk.cvar95)}
                note="mean loss beyond VaR" />
        <Metric label="Sharpe" value={num(risk.sharpe)} note="rf = 0" />
        <Metric label="Sortino" value={num(risk.sortino)} note="÷ downside vol" />
      </div>

      <div className="grid cols-3" style={{ marginTop: 16 }}>
        <Metric label="Dividend yield"
                value={instrument.dividendYield ? `${instrument.dividendYield}%` : "—"} />
        <Metric label="P/E" value={instrument.peRatio?.toFixed(2) ?? "—"} />
        <Metric label="Amihud illiquidity"
                value={risk.amihudIlliquidity ? risk.amihudIlliquidity.toExponential(2) : "—"}
                note="higher = thinner" />
      </div>
    </>
  );
}

function Metric({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="card">
      <h3>{label}</h3>
      <div className="value">{value}</div>
      {note && <div className="sub">{note}</div>}
    </div>
  );
}
