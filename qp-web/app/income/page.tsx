"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Cell,
} from "recharts";

const BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

const CURRENCIES = [
  { code: "MAD", label: "MAD — Moroccan dirham", symbol: "DH " },
  { code: "EUR", label: "EUR — Euro", symbol: "€" },
  { code: "USD", label: "USD — US dollar", symbol: "$" },
  { code: "GBP", label: "GBP — Pound sterling", symbol: "£" },
];

const pct = (n: number | null | undefined, d = 2) =>
  n === null || n === undefined ? "—" : `${(n * 100).toFixed(d)}%`;
const num = (n: number | null | undefined, d = 2) =>
  n === null || n === undefined ? "—"
    : n.toLocaleString("fr-MA", { minimumFractionDigits: d, maximumFractionDigits: d });

interface ScreenRow {
  ticker: string; name: string; sector: string | null; price: number;
  dividendPerShare: number; currentYield: number; yearsPaid: number;
  cuts: number; cagr: number | null; payoutRatio: number | null; trapLevel: string;
}

interface Income {
  ticker: string; name: string; sector: string;
  investment: {
    amount: number; currency: string; fxRate: number; fxSource: string;
    amountMad: number; sharePriceMad: number; shares: number;
    investedMad: number; uninvestedMad: number;
  };
  dividend: {
    trailingTwelveMonthsPerShare: number; currentYield: number;
    year1IncomeMad: number; year1IncomeTarget: number; monthlyEquivalentTarget: number;
  };
  history: {
    years: number; cagr: number | null; cuts: number;
    worstCut: { year: number; from: number; to: number; dropPct: number } | null;
    recoveredToHigh: boolean; quality: string; verdict: string;
    dataCaveat: string | null; series: { year: number; amount: number }[];
  };
  sustainability: {
    available: boolean; payoutRatio?: number; impliedEps?: number;
    risk?: string; verdict?: string; reason?: string;
  };
  yieldTrap: { level: string; signals: string[]; summary: string };
  scenarios: Record<string, {
    assumption: string; cumulativeMad: number; cumulativeTarget: number;
    yieldOnCostTotal: number | null;
  }>;
  risks: string[];
  disclaimer: string;
  priceChange1Y: number | null;
  marketMedianYield: number | null;
}

const trapClass = (level: string) =>
  level === "high" ? "down" : level === "moderate" ? "" : "up";

/** Renders **bold** without a markdown library. */
function Emphasised({ text }: { text: string }) {
  return (
    <>
      {text.split(/(\*\*[^*]+\*\*)/g).map((p, i) =>
        p.startsWith("**") && p.endsWith("**")
          ? <b key={i}>{p.slice(2, -2)}</b>
          : <span key={i}>{p}</span>
      )}
    </>
  );
}

export default function IncomePage() {
  const [screen, setScreen] = useState<ScreenRow[] | null>(null);
  const [medianYield, setMedianYield] = useState<number | null>(null);
  const [ticker, setTicker] = useState("ATW");
  const [amount, setAmount] = useState("100000");
  const [currency, setCurrency] = useState("MAD");
  const [fxRate, setFxRate] = useState("");
  const [result, setResult] = useState<Income | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`${BASE}/api/v1/analysis/income/screen?limit=30`, { cache: "no-store" })
      .then((r) => r.json())
      .then((d) => { setScreen(d.instruments || []); setMedianYield(d.medianYield ?? null); })
      .catch(() => setScreen([]));
  }, []);

  async function project(withTicker = ticker) {
    setBusy(true); setError(null);
    try {
      const body: Record<string, unknown> = {
        ticker: withTicker, amount: Number(amount), currency, horizonYears: 5,
      };
      if (fxRate.trim()) body.fxRate = Number(fxRate);
      const res = await fetch(`${BASE}/api/v1/analysis/income`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const d = await res.json();
      if (!d || !d.investment) throw new Error(d?.error || "could not project income");
      setResult(d);
    } catch (e) {
      setError(String(e).replace("Error: ", ""));
      setResult(null);
    } finally { setBusy(false); }
  }

  useEffect(() => { project("ATW"); /* eslint-disable-next-line */ }, []);

  const sym = CURRENCIES.find((c) => c.code === currency)?.symbol ?? `${currency} `;

  return (
    <>
      <h2 style={{ marginTop: 8 }}>Dividend income</h2>
      <p className="muted" style={{ maxWidth: 820, marginTop: 4 }}>
        What an investment would produce in dividend income — and, more to the point, what
        the data says about whether that income is likely to continue. A yield multiplied by
        an amount is one number; whether the company can keep paying it is the question.
      </p>

      <div className="card" style={{ marginTop: 16 }}>
        <div className="row">
          <div>
            <div className="sub" style={{ marginBottom: 4 }}>Amount</div>
            <input value={amount} onChange={(e) => setAmount(e.target.value)}
                   type="number" style={{ width: 150 }} />
          </div>
          <div>
            <div className="sub" style={{ marginBottom: 4 }}>Currency</div>
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}
                    style={{ width: 210 }}>
              {CURRENCIES.map((c) => <option key={c.code} value={c.code}>{c.label}</option>)}
            </select>
          </div>
          <div>
            <div className="sub" style={{ marginBottom: 4 }}>Instrument</div>
            <input value={ticker} onChange={(e) => setTicker(e.target.value.toUpperCase())}
                   style={{ width: 100 }} />
          </div>
          {currency !== "MAD" && (
            <div>
              <div className="sub" style={{ marginBottom: 4 }}>
                FX rate (1 MAD → {currency})
              </div>
              <input value={fxRate} onChange={(e) => setFxRate(e.target.value)}
                     placeholder="indicative" type="number" step="0.0001"
                     style={{ width: 130 }} />
            </div>
          )}
          <div style={{ alignSelf: "flex-end" }}>
            <button className="primary" onClick={() => project()} disabled={busy}>
              {busy ? "Projecting…" : "Project income"}
            </button>
          </div>
        </div>
        {error && <div className="error" style={{ marginTop: 10 }}>{error}</div>}
      </div>

      {result && (
        <>
          <div className="grid cols-4" style={{ marginTop: 16 }}>
            <div className="card">
              <h3>Shares purchased</h3>
              <div className="value">{result.investment.shares}</div>
              <div className="sub">
                at {num(result.investment.sharePriceMad)} MAD ·{" "}
                {num(result.investment.uninvestedMad)} MAD left over
              </div>
            </div>
            <div className="card">
              <h3>Year-1 income</h3>
              <div className="value up">
                {sym}{num(result.dividend.year1IncomeTarget)}
              </div>
              <div className="sub">
                {num(result.dividend.year1IncomeMad)} MAD ·{" "}
                {sym}{num(result.dividend.monthlyEquivalentTarget)}/month
              </div>
            </div>
            <div className="card">
              <h3>Current yield</h3>
              <div className="value">{pct(result.dividend.currentYield)}</div>
              <div className="sub">
                market median {pct(result.marketMedianYield)} · computed from dividends ÷ price
              </div>
            </div>
            <div className="card" style={{
              borderColor: result.yieldTrap.level === "high" ? "var(--down)"
                : result.yieldTrap.level === "moderate" ? "var(--accent)" : "var(--border)",
            }}>
              <h3>Yield-trap risk</h3>
              <div className={`value ${trapClass(result.yieldTrap.level)}`}>
                {result.yieldTrap.level}
              </div>
              <div className="sub">{result.yieldTrap.summary}</div>
            </div>
          </div>

          {result.yieldTrap.signals.length > 0 && (
            <div className="card" style={{ marginTop: 16, borderColor: "var(--down)" }}>
              <h3>Warning signals</h3>
              <ul className="sub" style={{ lineHeight: 1.9, paddingLeft: 18, margin: "6px 0 0" }}>
                {result.yieldTrap.signals.map((s, i) => <li key={i}>{s}</li>)}
              </ul>
            </div>
          )}

          <div className="section-title">
            Dividend record
            <span className="tag" style={{ marginLeft: 8, textTransform: "none" }}>
              {result.history.years} years · quality: {result.history.quality}
            </span>
          </div>
          <div className="card">
            <div style={{ height: 210 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={result.history.series}
                          margin={{ top: 14, right: 8, bottom: 0, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#232c3d" vertical={false} />
                  <XAxis dataKey="year" tick={{ fontSize: 11, fill: "#8b98ad" }}
                         axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 11, fill: "#8b98ad" }} width={46}
                         axisLine={false} tickLine={false} />
                  <Tooltip contentStyle={{ background: "#131822", border: "1px solid #232c3d",
                                           borderRadius: 8, fontSize: 12 }}
                           formatter={(v) => [`${v} MAD/share`, "dividend"]} />
                  <Bar dataKey="amount" radius={[3, 3, 0, 0]}>
                    {result.history.series.map((d, i, arr) => (
                      // Red when a year paid less than the year before.
                      <Cell key={i} fill={i > 0 && d.amount < arr[i - 1].amount * 0.99
                        ? "#e0524c" : "#d4a12a"} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
            <div className="sub" style={{ marginTop: 8, lineHeight: 1.7 }}>
              <b>{result.history.verdict}</b>
              {result.history.cagr !== null && <> Growth has averaged {pct(result.history.cagr)} a year.</>}
              {result.history.dataCaveat && (
                <div style={{ marginTop: 8, color: "var(--accent)" }}>
                  ⚠ {result.history.dataCaveat}
                </div>
              )}
            </div>
          </div>

          <div className="grid cols-2" style={{ marginTop: 16 }}>
            <div className="card">
              <h3>Payout ratio</h3>
              {result.sustainability.available ? (
                <>
                  <div className={`value ${(result.sustainability.payoutRatio ?? 0) > 1 ? "down"
                    : (result.sustainability.payoutRatio ?? 0) > 0.7 ? "" : "up"}`}>
                    {pct(result.sustainability.payoutRatio, 0)}
                  </div>
                  <div className="sub">{result.sustainability.verdict}</div>
                  <div className="sub" style={{ marginTop: 6 }}>
                    Implied EPS {num(result.sustainability.impliedEps)} MAD, derived as price ÷ P/E.
                  </div>
                </>
              ) : (
                <>
                  <div className="value muted">unavailable</div>
                  <div className="sub">
                    {result.sustainability.reason} — reported as unknown rather than
                    estimated, because a fabricated coverage figure invites a decision.
                  </div>
                </>
              )}
            </div>
            <div className="card">
              <h3>Price over the last year</h3>
              <div className={`value ${(result.priceChange1Y ?? 0) >= 0 ? "up" : "down"}`}>
                {result.priceChange1Y === null ? "—" : pct(result.priceChange1Y)}
              </div>
              <div className="sub" style={{ lineHeight: 1.6 }}>
                Yield is dividend ÷ price. A falling price raises the yield without the
                company paying a dirham more — which is exactly why this belongs beside it.
              </div>
            </div>
          </div>

          <div className="section-title">
            Five-year projection
            <span className="tag" style={{ marginLeft: 8, textTransform: "none" }}>
              three scenarios — one number would imply precision the inputs lack
            </span>
          </div>
          <div className="card" style={{ padding: 0, overflowX: "auto" }}>
            <table>
              <thead>
                <tr>
                  <th style={{ textAlign: "left" }}>Scenario</th>
                  <th style={{ textAlign: "left" }}>Assumption</th>
                  <th>Cumulative ({currency})</th>
                  <th>Cumulative (MAD)</th>
                  <th>Yield on cost</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(result.scenarios).map(([name, sc]) => (
                  <tr key={name}>
                    <td style={{ textAlign: "left" }}>
                      <b className={name === "cut" ? "down" : name === "historical" ? "up" : ""}>
                        {name}
                      </b>
                    </td>
                    <td style={{ textAlign: "left" }} className="muted">{sc.assumption}</td>
                    <td><b>{sym}{num(sc.cumulativeTarget)}</b></td>
                    <td className="muted">{num(sc.cumulativeMad)}</td>
                    <td>{pct(sc.yieldOnCostTotal)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="section-title">What could go wrong</div>
          <div className="card">
            <ul className="sub" style={{ lineHeight: 1.9, paddingLeft: 18, margin: 0 }}>
              {result.risks.map((r, i) => <li key={i}><Emphasised text={r} /></li>)}
            </ul>
            <div className="sub" style={{ marginTop: 14, paddingTop: 12,
                                          borderTop: "1px solid var(--border)" }}>
              {result.disclaimer}
            </div>
          </div>
        </>
      )}

      <div className="section-title">
        Dividend payers, ranked by yield
        {medianYield !== null && (
          <span className="tag" style={{ marginLeft: 8, textTransform: "none" }}>
            market median {pct(medianYield)}
          </span>
        )}
      </div>
      <div className="card" style={{ padding: 0, overflowX: "auto" }}>
        <table>
          <thead>
            <tr>
              <th>Ticker</th><th style={{ textAlign: "left" }}>Name</th>
              <th>Yield</th><th>DPS</th><th>Years</th><th>Cuts</th>
              <th>Growth</th><th>Payout</th><th>Trap</th>
            </tr>
          </thead>
          <tbody>
            {(screen ?? []).map((r) => (
              <tr key={r.ticker} onClick={() => { setTicker(r.ticker); project(r.ticker); }}>
                <td><b>{r.ticker}</b></td>
                <td style={{ textAlign: "left" }} className="muted">{r.name}</td>
                <td><b>{pct(r.currentYield)}</b></td>
                <td className="muted">{num(r.dividendPerShare)}</td>
                <td className="muted">{r.yearsPaid}</td>
                <td className={r.cuts > 0 ? "down" : "up"}>{r.cuts}</td>
                <td className={(r.cagr ?? 0) >= 0 ? "up" : "down"}>{pct(r.cagr, 1)}</td>
                <td className={(r.payoutRatio ?? 0) > 1 ? "down" : "muted"}>
                  {r.payoutRatio === null ? "—" : pct(r.payoutRatio, 0)}
                </td>
                <td className={trapClass(r.trapLevel)}>{r.trapLevel}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {screen === null && <div className="empty">Loading screen…</div>}
        <div className="sub" style={{ padding: "12px 16px", lineHeight: 1.7 }}>
          Sorted by yield, but <b>read the cuts, growth and payout columns first</b>. The
          highest-yielding names in any market are disproportionately those whose price has
          fallen because the market expects a cut. A payout ratio above 100% means the
          dividend is not funded by earnings. Click a row to project it above.
        </div>
      </div>

      <p className="muted" style={{ fontSize: 12, marginTop: 20, maxWidth: 820 }}>
        Dividend history is real for ATW; simulated for the other instruments, since
        fetching it for all 81 would cost 81 of the data vendor&apos;s 100 daily requests.
        See the{" "}
        <Link href="/analysis" style={{ textDecoration: "underline" }}>analysis page</Link>{" "}
        for full provenance. Informational analysis, not investment advice.
      </p>
    </>
  );
}
