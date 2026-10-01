"use client";

import { useEffect, useState } from "react";
import {
  ScatterChart, Scatter, XAxis, YAxis, ZAxis, Tooltip, ResponsiveContainer,
  CartesianGrid, BarChart, Bar, AreaChart, Area, Legend, Cell,
} from "recharts";
import { WorldComparison } from "@/components/WorldComparison";

const BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

async function fetchJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { cache: "no-store" });
  if (!res.ok) throw new Error(`${res.status}`);
  return res.json();
}

const pct = (n: number | null | undefined, d = 1) =>
  n === null || n === undefined ? "—" : `${(n * 100).toFixed(d)}%`;

/** Red to green scale for correlation. */
function corrColour(v: number | null): string {
  if (v === null) return "var(--panel-2)";
  const t = Math.max(-1, Math.min(1, v));
  return t >= 0
    ? `rgba(38,163,123,${0.12 + 0.78 * t})`
    : `rgba(224,82,76,${0.12 + 0.78 * -t})`;
}

interface MarketReport {
  range: string;
  universe: string[];
  sessions: number;
  correlation: {
    order: string[];
    matrix: (number | null)[][];
    average: number;
    clusters: { ticker: string; cluster: number | null; sector: string | null }[];
  };
  factors: {
    available: boolean;
    explainedVariance?: number[];
    cumulativeExplained?: number[];
    interpretation?: string;
    pc1Loadings?: Record<string, number>;
    instruments?: number;
  };
  sectors: {
    sector: string; instruments: number; annualisedVolatility: number;
    annualisedReturn: number; sharpe: number | null; maxDrawdown: number;
    avgPairwiseCorrelation: number | null;
  }[];
}

interface Forecast {
  ticker: string; spot: number; horizonDays: number; simulations: number;
  annualisedVolatility: number; volEstimator: string;
  bands: Record<string, number[]>;
  terminal: {
    median: number; probAboveSpot: number; probDown10Pct: number; probUp10Pct: number;
  };
  assumptions: string[];
  calibration?: {
    available: boolean; trials?: number;
    coverage?: Record<string, { nominal: number; empirical: number; verdict: string }>;
  };
  calibrationComparison?: Record<string, Record<string, { nominal: number; empirical: number }> | null>;
}

export default function AnalysisPage() {
  const [report, setReport] = useState<MarketReport | null>(null);
  const [forecast, setForecast] = useState<Forecast | null>(null);
  const [ticker, setTicker] = useState("ATW");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchJson<MarketReport>("/api/v1/analysis/market-report?range=1Y&limit=30")
      .then(setReport)
      .catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    setForecast(null);
    fetchJson<Forecast>(`/api/v1/analysis/forecast/${ticker}?horizon=60&range=1Y`)
      .then(setForecast)
      .catch(() => {});
  }, [ticker]);

  // The comparison is fast, the report below can take seconds.
  // Same position in both branches so the chart isn't remounted.
  if (error || !report) {
    return (
      <>
        <h2 style={{ marginTop: 8 }}>Market analysis</h2>
        <WorldComparison />
        {error
          ? <div className="error" style={{ marginTop: 20 }}>Analytics unavailable: {error}</div>
          : <div className="empty">Running market analysis… (this is real computation, not a cached page)</div>}
      </>
    );
  }

  const { correlation, factors, sectors } = report;
  const scree = (factors.explainedVariance || []).map((v, i) => ({
    name: `PC${i + 1}`, value: +(v * 100).toFixed(2),
  }));
  const sectorPoints = sectors
    .filter((s) => s.instruments >= 2)
    .map((s) => ({
      sector: s.sector,
      vol: +(s.annualisedVolatility * 100).toFixed(2),
      ret: +(s.annualisedReturn * 100).toFixed(2),
      n: s.instruments,
      sharpe: s.sharpe ?? 0,
    }));

  // Recharts draws a band when the dataKey gives a [low, high] pair.
  // The old version faked it with stacked areas, which broke in light mode.
  const fanData = forecast
    ? forecast.bands.p50.map((_, i) => ({
        day: i + 1,
        band90: [forecast.bands.p5[i], forecast.bands.p95[i]] as [number, number],
        band50: [forecast.bands.p25[i], forecast.bands.p75[i]] as [number, number],
        median: forecast.bands.p50[i],
      }))
    : [];

  return (
    <>
      <h2 style={{ marginTop: 8 }}>Market analysis</h2>
      <WorldComparison />
      <p className="muted" style={{ maxWidth: 780, marginTop: 28 }}>
        Computed on demand by the Python analytics service over {report.sessions} sessions
        of stored OHLCV for {correlation.order.length} instruments. Nothing here is cached
        or precomputed.
      </p>

      <div className="card" style={{ marginTop: 12, borderColor: "var(--accent)" }}>
        <h3>Data provenance</h3>
        <div className="sub" style={{ lineHeight: 1.6 }}>
          Reference data (tickers, sectors, market caps, current prices) is real, recorded
          from the Drahmi API. Price <em>history</em> is real for <b>ATW, IAM and MNG</b>;
          the other 78 instruments use a factor-model simulation, because fetching a year of
          bars for all 81 would cost 81 of the vendor&apos;s 100 daily requests. Read the
          cross-sectional panels as correct methodology on data with known structure.
        </div>
      </div>

      {/* Factor structure */}
      <div className="section-title">Factor structure — does this market move as one thing?</div>
      <div className="grid cols-2">
        <div className="card">
          <h3>Variance explained by component</h3>
          <div style={{ height: 220, marginTop: 8 }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={scree}>
                <CartesianGrid strokeDasharray="3 3" stroke="#232c3d" vertical={false} />
                <XAxis dataKey="name" tick={{ fontSize: 11, fill: "#8b98ad" }} axisLine={false} tickLine={false} />
                <YAxis unit="%" tick={{ fontSize: 11, fill: "#8b98ad" }} axisLine={false} tickLine={false} width={42} />
                <Tooltip contentStyle={{ background: "#131822", border: "1px solid #232c3d", borderRadius: 8, fontSize: 12 }} />
                <Bar dataKey="value" fill="#d4a12a" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="sub" style={{ marginTop: 8 }}>{factors.interpretation}</div>
        </div>

        <div className="card">
          <h3>Average pairwise correlation</h3>
          <div className="value" style={{ fontSize: 34 }}>{correlation.average.toFixed(3)}</div>
          <div className="sub" style={{ marginTop: 8, lineHeight: 1.6 }}>
            The mean off-diagonal correlation across {correlation.order.length} instruments.
            A high value means diversifying <em>inside</em> this exchange has a hard floor —
            risk reduction requires assets outside it.
          </div>
          <div className="sub" style={{ marginTop: 10 }}>
            PC1 loadings all share a sign, which is the signature of a genuine market factor
            rather than a contrast between two groups of stocks.
          </div>
        </div>
      </div>

      {/* Correlation heatmap */}
      <div className="section-title">
        Correlation matrix
        <span className="tag" style={{ marginLeft: 8, textTransform: "none" }}>
          ordered by hierarchical clustering, not alphabetically
        </span>
      </div>
      <div className="card" style={{ overflowX: "auto" }}>
        <table style={{ borderCollapse: "collapse", fontSize: 9 }}>
          <thead>
            <tr>
              <th style={{ padding: 2, border: "none" }}></th>
              {correlation.order.map((t) => (
                <th key={t} style={{
                  padding: 2, border: "none", writingMode: "vertical-rl",
                  fontSize: 9, height: 52, color: "var(--muted)",
                }}>{t}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {correlation.order.map((rowTicker, i) => (
              <tr key={rowTicker}>
                <td style={{
                  padding: "1px 6px", border: "none", textAlign: "right",
                  fontSize: 9, color: "var(--muted)", whiteSpace: "nowrap",
                }}>{rowTicker}</td>
                {correlation.matrix[i].map((v, j) => (
                  <td key={j}
                      title={`${rowTicker} / ${correlation.order[j]}: ${v ?? "n/a"}`}
                      style={{
                        padding: 0, border: "none",
                        width: 15, height: 15,
                        background: corrColour(v),
                      }} />
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        <div className="sub" style={{ marginTop: 12 }}>
          Green = positive, red = negative. Blocks along the diagonal are groups that move
          together. The clustering never sees sector labels, so any agreement with the
          official classification is evidence rather than assumption.
        </div>
      </div>

      {/* Sector risk/return */}
      <div className="section-title">Sector risk and return</div>
      <div className="card">
        <div style={{ height: 320 }}>
          <ResponsiveContainer width="100%" height="100%">
            <ScatterChart margin={{ top: 10, right: 20, bottom: 20, left: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#232c3d" />
              <XAxis type="number" dataKey="vol" name="Volatility" unit="%"
                     tick={{ fontSize: 11, fill: "#8b98ad" }}
                     label={{ value: "Annualised volatility (%)", position: "insideBottom",
                              offset: -10, fill: "#8b98ad", fontSize: 11 }} />
              <YAxis type="number" dataKey="ret" name="Return" unit="%"
                     tick={{ fontSize: 11, fill: "#8b98ad" }} width={54}
                     label={{ value: "Return (%)", angle: -90, position: "insideLeft",
                              fill: "#8b98ad", fontSize: 11 }} />
              <ZAxis type="number" dataKey="n" range={[60, 400]} />
              <Tooltip
                cursor={{ strokeDasharray: "3 3" }}
                contentStyle={{ background: "#131822", border: "1px solid #232c3d",
                                borderRadius: 8, fontSize: 12 }}
                content={({ payload }) => {
                  if (!payload?.length) return null;
                  const d = payload[0].payload;
                  return (
                    <div style={{ background: "#131822", border: "1px solid #232c3d",
                                  borderRadius: 8, padding: 10, fontSize: 12 }}>
                      <b>{d.sector}</b><br />
                      return {d.ret}% · vol {d.vol}%<br />
                      Sharpe {d.sharpe.toFixed(2)} · {d.n} instruments
                    </div>
                  );
                }} />
              <Scatter data={sectorPoints}>
                {sectorPoints.map((p, i) => (
                  <Cell key={i} fill={p.sharpe >= 0 ? "#26a37b" : "#e0524c"} fillOpacity={0.75} />
                ))}
              </Scatter>
            </ScatterChart>
          </ResponsiveContainer>
        </div>
        <div className="sub">
          Each sector is treated as an equal-weighted portfolio of its members, not as an
          average of their individual statistics — averaging volatilities discards the
          diversification between them and overstates sector risk.
        </div>
      </div>

      {/* Forecast */}
      <div className="row" style={{ marginTop: 28, justifyContent: "space-between" }}>
        <div className="section-title" style={{ margin: 0 }}>
          Distributional forecast
        </div>
        <div className="row">
          {["ATW", "IAM", "MNG"].map((t) => (
            <button key={t} className={t === ticker ? "primary" : ""} onClick={() => setTicker(t)}>
              {t}
            </button>
          ))}
          <span className="tag">real history</span>
        </div>
      </div>

      {!forecast ? (
        <div className="empty">Simulating…</div>
      ) : (
        <>
          <div className="card">
            <div className="row" style={{ justifyContent: "space-between", marginBottom: 6 }}>
              <div>
                <div className="value">{forecast.ticker} — {forecast.horizonDays} sessions ahead</div>
                <div className="sub">
                  spot {forecast.spot} MAD · {forecast.simulations.toLocaleString()} paths ·
                  annualised vol {pct(forecast.annualisedVolatility)} ·
                  σ estimator <b>{forecast.volEstimator}</b>
                </div>
              </div>
            </div>
            <div style={{ height: 280 }}>
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={fanData} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#232c3d" vertical={false} />
                  <XAxis dataKey="day" tick={{ fontSize: 11, fill: "#8b98ad" }}
                         axisLine={false} tickLine={false} />
                  <YAxis domain={["auto", "auto"]} tick={{ fontSize: 11, fill: "#8b98ad" }}
                         axisLine={false} tickLine={false} width={54} />
                  <Tooltip contentStyle={{ background: "#131822", border: "1px solid #232c3d",
                                           borderRadius: 8, fontSize: 12 }} />
                  <Legend wrapperStyle={{ fontSize: 11 }} />
                  <Area type="monotone" dataKey="band90" stroke="none"
                        fill="#d4a12a" fillOpacity={0.18} name="90% interval" />
                  <Area type="monotone" dataKey="band50" stroke="none"
                        fill="#d4a12a" fillOpacity={0.38} name="50% interval" />
                  <Area type="monotone" dataKey="median" stroke="#b8860b" strokeWidth={2}
                        strokeDasharray="4 3" fill="none" name="median path" />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </div>

          <div className="grid cols-3" style={{ marginTop: 16 }}>
            <div className="card">
              <h3>P(above spot)</h3>
              <div className="value">{pct(forecast.terminal.probAboveSpot)}</div>
              <div className="sub">a point forecast cannot express this</div>
            </div>
            <div className="card">
              <h3>P(down &gt; 10%)</h3>
              <div className="value down">{pct(forecast.terminal.probDown10Pct)}</div>
              <div className="sub">the number a risk manager asks for</div>
            </div>
            <div className="card">
              <h3>P(up &gt; 10%)</h3>
              <div className="value up">{pct(forecast.terminal.probUp10Pct)}</div>
              <div className="sub">median {forecast.terminal.median} MAD</div>
            </div>
          </div>

          {forecast.calibration?.available && (
            <>
              <div className="section-title">
                Calibration backtest
                <span className="tag" style={{ marginLeft: 8, textTransform: "none" }}>
                  {forecast.calibration.trials} out-of-sample windows
                </span>
              </div>
              <div className="card" style={{ padding: 0, overflowX: "auto" }}>
                <table>
                  <thead>
                    <tr>
                      <th style={{ textAlign: "left" }}>Nominal</th>
                      <th>Empirical</th>
                      <th style={{ textAlign: "left" }}>Verdict</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(forecast.calibration.coverage || {}).map(([k, v]) => {
                      const off = Math.abs(v.empirical - v.nominal);
                      return (
                        <tr key={k}>
                          <td style={{ textAlign: "left" }}><b>{pct(v.nominal, 0)}</b></td>
                          <td className={off <= 0.05 ? "up" : "down"}>{pct(v.empirical)}</td>
                          <td style={{ textAlign: "left" }} className="muted">{v.verdict}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
                <div className="sub" style={{ padding: "12px 16px", lineHeight: 1.6 }}>
                  Walk-forward: parameters are re-fit on a rolling window using only data
                  before each cut-off, so no future information reaches the fit. A
                  well-calibrated 80% interval should contain the outcome about 80% of the
                  time. <b>Below</b> nominal means the model is overconfident — it
                  understates risk, which is the dangerous direction.
                </div>
              </div>
            </>
          )}

          <div className="card" style={{ marginTop: 16 }}>
            <h3>Stated assumptions</h3>
            <ul className="sub" style={{ lineHeight: 1.7, paddingLeft: 18, margin: "6px 0 0" }}>
              {forecast.assumptions.map((a, i) => <li key={i}>{a}</li>)}
            </ul>
          </div>
        </>
      )}
    </>
  );
}
