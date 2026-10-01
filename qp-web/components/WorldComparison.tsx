"use client";

import { useEffect, useState } from "react";
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Legend, ReferenceLine,
} from "recharts";
import { api, type BenchmarkComparison } from "@/lib/api";

const COLOURS: Record<string, string> = { MASI: "#d4a12a", SPY: "#4c8bf5", EEM: "#26a37b" };
const RANGES = ["3M", "6M", "1Y"] as const;

const signed = (n: number | null | undefined) =>
  n == null ? "—" : `${n >= 0 ? "+" : ""}${n.toFixed(1)}%`;

/** MASI vs the S&P 500 and emerging markets, all in dirhams. */
export function WorldComparison() {
  const [range, setRange] = useState<(typeof RANGES)[number]>("1Y");
  const [data, setData] = useState<BenchmarkComparison | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    setData(null);
    setError(false);
    api.compareBenchmarks(range).then(setData).catch(() => setError(true));
  }, [range]);

  const rows = (data?.points ?? []).map((p) => ({ date: p.date, ...p.values }));

  return (
    <>
      <div className="row" style={{ marginTop: 28, marginBottom: 12, justifyContent: "space-between" }}>
        <div className="section-title" style={{ margin: 0 }}>
          Casablanca vs the world — in dirhams
          {data?.sampleData && <span className="badge sample" style={{ marginLeft: 10 }}>Sample data</span>}
        </div>
        <div className="seg">
          {RANGES.map((r) => (
            <button key={r} className={r === range ? "on" : ""} onClick={() => setRange(r)}>{r}</button>
          ))}
        </div>
      </div>

      <div className="card">
        {error && <div className="error">Comparison unavailable.</div>}
        {!data && !error && <div className="empty">Loading comparison…</div>}
        {data && (
          <>
            <p className="verdict">{data.verdict}</p>

            {rows.length > 0 && (
              <>
                <div className="grid cols-4" style={{ marginBottom: 12 }}>
                  {data.series.map((s) => (
                    <div key={s.code}>
                      <div className="muted" style={{ fontSize: 12 }}>
                        <span style={{ color: COLOURS[s.code] }}>●</span> {s.label}
                      </div>
                      <div style={{ fontSize: 20, fontWeight: 650 }} className={(s.returnMadPercent ?? 0) >= 0 ? "up" : "down"}>
                        {signed(s.returnMadPercent)}
                      </div>
                      <div className="muted" style={{ fontSize: 11 }}>
                        in MAD{s.returnLocalPercent != null && ` · ${signed(s.returnLocalPercent)} in ${s.currency}`}
                      </div>
                    </div>
                  ))}
                  <div>
                    <div className="muted" style={{ fontSize: 12 }}>USD/MAD</div>
                    <div style={{ fontSize: 20, fontWeight: 650 }}>{signed(data.usdMadChangePercent)}</div>
                    <div className="muted" style={{ fontSize: 11 }}>
                      {data.usdMadChangePercent != null && data.usdMadChangePercent < 0 ? "dirham stronger" : "dirham weaker"}
                    </div>
                  </div>
                </div>

                <div style={{ width: "100%", height: 300 }}>
                  <ResponsiveContainer>
                    <LineChart data={rows} margin={{ top: 4, right: 8, left: -12, bottom: 0 }}>
                      <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
                      <XAxis dataKey="date" tick={{ fontSize: 11, fill: "var(--muted)" }} minTickGap={40} />
                      <YAxis domain={["auto", "auto"]} tick={{ fontSize: 11, fill: "var(--muted)" }} />
                      <ReferenceLine y={100} stroke="var(--muted)" strokeDasharray="4 4" />
                      <Tooltip
                        contentStyle={{ background: "var(--panel)", border: "1px solid var(--border)", fontSize: 12 }}
                        formatter={(v, name) => [typeof v === "number" ? v.toFixed(2) : String(v), String(name)]}
                      />
                      <Legend wrapperStyle={{ fontSize: 12 }} />
                      {data.series.map((s) => (
                        <Line key={s.code} dataKey={s.code} name={s.label} stroke={COLOURS[s.code] ?? "#888"}
                              dot={false} strokeWidth={s.code === "MASI" ? 2.2 : 1.6} isAnimationActive={false} />
                      ))}
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </>
            )}

            <details className="sources">
              <summary>Method and sources</summary>
              <ul style={{ margin: "6px 0", paddingLeft: 18, lineHeight: 1.6 }}>
                <li>Rebased to 100 on {data.start ?? "—"}; window ends {data.end ?? "—"}.</li>
                {data.notes.map((n) => <li key={n}>{n}</li>)}
                {data.sources.map((s) => (
                  <li key={s.series}>
                    {s.series}: {s.sources.join(", ") || "—"}
                    {s.sources.includes("SYNTHETIC") && " — synthetic until recorded with ops/tools/record_alphavantage.py"}
                  </li>
                ))}
              </ul>
            </details>
          </>
        )}
      </div>
    </>
  );
}
