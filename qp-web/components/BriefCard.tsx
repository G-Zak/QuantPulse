"use client";

import { useEffect, useState } from "react";
import { api, getToken, type Brief } from "@/lib/api";

/**
 * Daily market brief. Shows if AI or the template wrote it, why the template
 * was used, and which facts it used.
 */
export function BriefCard() {
  const [brief, setBrief] = useState<Brief | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [canRegenerate, setCanRegenerate] = useState(false);

  useEffect(() => {
    setCanRegenerate(!!getToken());
    api.brief().then((b) => setBrief(b ?? null)).catch(() => setBrief(null));
  }, []);

  async function regenerate() {
    setBusy(true);
    setError(null);
    try {
      setBrief(await api.regenerateBrief());
    } catch (e) {
      setError(String(e).includes("unauthorised") ? "Sign in to regenerate." : "Regeneration failed.");
      setCanRegenerate(!!getToken());
    } finally {
      setBusy(false);
    }
  }

  if (brief === undefined) {
    return <div className="card span-2"><h3>Today on the Bourse</h3><div className="sub">Loading brief…</div></div>;
  }
  if (brief === null) {
    return (
      <div className="card span-2">
        <h3>Today on the Bourse</h3>
        <div className="sub">No brief yet — one is written within 10 minutes of the insights service starting.</div>
      </div>
    );
  }

  const byAi = brief.source === "CLAUDE";
  const when = new Date(brief.generatedAt).toLocaleString("en-GB", {
    day: "numeric", month: "short", hour: "2-digit", minute: "2-digit",
  });

  return (
    <div className="card span-2">
      <div className="row" style={{ justifyContent: "space-between", gap: 8 }}>
        <h3 style={{ margin: 0 }}>Today on the Bourse</h3>
        <div className="row" style={{ gap: 6 }}>
          {byAi
            ? <span className="badge ai" title={`Written by ${brief.model}; every number verified against the facts`}>✦ AI · {brief.model}</span>
            : <span className="badge" title={brief.fallbackReason ?? ""}>Template</span>}
          {brief.sampleData && <span className="badge sample" title="Some figures are sample data">Sample data</span>}
        </div>
      </div>

      <p className="brief-headline">{brief.headline}</p>
      <p className="brief-body">{brief.body}</p>

      <details className="sources">
        <summary>
          How this was written · {when}
        </summary>
        <div style={{ marginTop: 6, lineHeight: 1.6 }}>
          {byAi ? (
            <>
              Written by {brief.model} from a fixed set of facts, then checked: every number in
              the text had to match a fact, and advice or forecasts were not allowed.
              {brief.inputTokens != null && <> {brief.inputTokens} input / {brief.outputTokens} output tokens.</>}
            </>
          ) : (
            <>Deterministic template. Reason: {brief.fallbackReason}</>
          )}
          {brief.factsUsed.length > 0 && (
            <div>Facts used: {brief.factsUsed.map((f) => <code key={f}>{f} </code>)}</div>
          )}
          <div>
            AI budget left today: {brief.budgetRemaining}
            {!brief.aiEnabled && " (AI disabled — set ANTHROPIC_API_KEY)"}
          </div>
        </div>
      </details>

      {canRegenerate && (
        <div className="row" style={{ marginTop: 10 }}>
          <button onClick={regenerate} disabled={busy}>
            {busy ? "Writing…" : "Regenerate"}
          </button>
          {error && <span className="error">{error}</span>}
        </div>
      )}
    </div>
  );
}
