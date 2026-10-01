"use client";

import { useCallback, useEffect, useState } from "react";
import { Login } from "@/components/Login";
import { api, getToken, type AlertFiring, type AlertRule } from "@/lib/api";

const TYPES = [
  { value: "PRICE_ABOVE", label: "Price rises above" },
  { value: "PRICE_BELOW", label: "Price falls below" },
  { value: "PERCENT_MOVE_UP", label: "Gains more than (%)" },
  { value: "PERCENT_MOVE_DOWN", label: "Drops more than (%)" },
];

export default function AlertsPage() {
  const [authed, setAuthed] = useState<boolean | null>(null);
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [firings, setFirings] = useState<AlertFiring[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [ticker, setTicker] = useState("ATW");
  const [type, setType] = useState("PRICE_ABOVE");
  const [threshold, setThreshold] = useState("700");

  useEffect(() => setAuthed(!!getToken()), []);

  const load = useCallback(async () => {
    const [r, f] = await Promise.all([api.alerts(), api.firings()]);
    setRules(r);
    setFirings(f);
  }, []);

  useEffect(() => { if (authed) load().catch((e) => setError(String(e))); }, [authed, load]);

  // Alerts fire in the background, so we poll. It only hits our BFF, not the upstream API.
  useEffect(() => {
    if (!authed) return;
    const t = setInterval(() => load().catch(() => {}), 5000);
    return () => clearInterval(t);
  }, [authed, load]);

  if (authed === null) return <div className="empty">…</div>;
  if (!authed) return <Login onSuccess={() => setAuthed(true)} />;

  return (
    <>
      <h2 style={{ marginTop: 8 }}>Alerts</h2>

      <div className="card" style={{ marginTop: 16 }}>
        <div className="row">
          <input value={ticker} onChange={(e) => setTicker(e.target.value.toUpperCase())}
                 placeholder="Ticker" style={{ width: 100 }} />
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
          <input value={threshold} onChange={(e) => setThreshold(e.target.value)}
                 type="number" step="0.01" style={{ width: 120 }} />
          <button className="primary" onClick={async () => {
            try {
              await api.createAlert({
                ticker, type, threshold: Number(threshold),
                hysteresisPct: 0.5, cooldownSeconds: 3600,
              });
              await load();
            } catch (e) { setError(String(e)); }
          }}>Create</button>
        </div>
        <div className="sub" style={{ marginTop: 8 }}>
          Every rule gets 0.5% hysteresis and a 1-hour cooldown, so a price hovering at
          the threshold produces one alert rather than one per poll.
        </div>
        {error && <div className="error" style={{ marginTop: 8 }}>{error}</div>}
      </div>

      <div className="section-title">Rules</div>
      <div className="card" style={{ padding: 0, overflowX: "auto" }}>
        <table>
          <thead>
            <tr>
              <th>Ticker</th><th style={{ textAlign: "left" }}>Condition</th>
              <th>Threshold</th><th>State</th><th>Fired</th><th></th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id}>
                <td><b>{r.ticker}</b></td>
                <td style={{ textAlign: "left" }} className="muted">
                  {TYPES.find((t) => t.value === r.type)?.label ?? r.type}
                </td>
                <td>{r.threshold}</td>
                <td>
                  <span className="tag">
                    {!r.enabled ? "disabled" : r.armed ? "armed" : "cooling down"}
                  </span>
                </td>
                <td>{r.fireCount}</td>
                <td>
                  <button onClick={async () => { await api.deleteAlert(r.id); await load(); }}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {rules.length === 0 && <div className="empty">No alert rules yet.</div>}
      </div>

      <div className="section-title">Recent firings</div>
      <div className="card" style={{ padding: 0, overflowX: "auto" }}>
        <table>
          <thead>
            <tr>
              <th style={{ textAlign: "left" }}>When</th>
              <th style={{ textAlign: "left" }}>Reason</th><th>Price</th>
            </tr>
          </thead>
          <tbody>
            {firings.map((f) => (
              <tr key={f.id}>
                <td style={{ textAlign: "left" }} className="muted">
                  {new Date(f.firedAt).toLocaleString("fr-MA")}
                </td>
                <td style={{ textAlign: "left" }}>{f.reason}</td>
                <td>{f.triggeredPrice}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {firings.length === 0 && <div className="empty">Nothing has fired yet.</div>}
      </div>
    </>
  );
}
