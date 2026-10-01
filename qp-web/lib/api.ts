const BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
const TOKEN_KEY = "qp_token";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  window.localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers || {}),
    },
    cache: "no-store",
  });

  if (res.status === 401 || res.status === 403) {
    // Token expired or invalid. Clear it so the login form shows instead of failing requests.
    clearToken();
    throw new Error("unauthorised");
  }
  if (!res.ok) {
    throw new Error(`${res.status} ${await res.text()}`);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

export const api = {
  login: (username: string, password: string) =>
    request<{ token: string; username: string; roles: string[] }>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),

  dashboard: () => request<Dashboard>("/api/v1/market/dashboard"),
  instrumentDetail: (ticker: string, range = "6M") =>
    request<InstrumentDetail>(`/api/v1/market/instruments/${ticker}/detail?range=${range}`),
  sectors: () => request<Sector[]>("/api/v1/market/sectors"),
  quota: () => request<Quota>("/api/v1/market/quota"),
  fxRates: (pairs = "USD-MAD,EUR-MAD") => request<FxQuote[]>(`/api/v1/market/fx/rates?pairs=${pairs}`),
  compareBenchmarks: (range = "1Y") =>
    request<BenchmarkComparison>(`/api/v1/market/benchmarks/compare?range=${range}`),
  brief: () => request<Brief | undefined>("/api/v1/market/brief"),
  regenerateBrief: () => request<Brief>("/api/v1/market/brief/regenerate", { method: "POST" }),

  portfolios: () => request<Portfolio[]>("/api/v1/portfolios"),
  createPortfolio: (name: string) =>
    request<Portfolio>("/api/v1/portfolios", {
      method: "POST",
      body: JSON.stringify({ name, baseCurrency: "MAD" }),
    }),
  positions: (id: string) => request<Position[]>(`/api/v1/portfolios/${id}/positions`),
  summary: (id: string, currency = "MAD") =>
    request<PortfolioSummary>(`/api/v1/portfolios/${id}/summary?currency=${currency}`),
  buy: (id: string, body: TradeBody) =>
    request(`/api/v1/portfolios/${id}/buy`, { method: "POST", body: JSON.stringify(body) }),
  sell: (id: string, body: TradeBody) =>
    request(`/api/v1/portfolios/${id}/sell`, { method: "POST", body: JSON.stringify(body) }),

  alerts: () => request<AlertRule[]>("/api/v1/alerts"),
  createAlert: (body: NewAlert) =>
    request<AlertRule>("/api/v1/alerts", { method: "POST", body: JSON.stringify(body) }),
  deleteAlert: (id: string) => request(`/api/v1/alerts/${id}`, { method: "DELETE" }),
  firings: () => request<AlertFiring[]>("/api/v1/alerts/firings?limit=50"),
};

/** Subscribes to live prices. EventSource reconnects by itself if the connection drops. */
export function subscribePrices(
  tickers: string[],
  onPrice: (tick: PriceTick) => void
): () => void {
  const q = tickers.length ? `?tickers=${tickers.join(",")}` : "";
  const es = new EventSource(`${BASE}/api/v1/stream${q}`);
  es.addEventListener("price", (e) => {
    try {
      onPrice(JSON.parse((e as MessageEvent).data));
    } catch {
      /* skip a bad message, keep the stream open */
    }
  });
  return () => es.close();
}

// Types

export interface Instrument {
  ticker: string; name: string; sector: string | null; currency: string;
  price: number | null; priceAt: string | null; marketCap: number | null;
  dividendYield: number | null; peRatio: number | null;
  week52High: number | null; week52Low: number | null;
  changePercent: number | null;
}
/** Where a number comes from. SYNTHETIC must be shown as sample data. */
export type DataSource = "ALPHAVANTAGE" | "FIXTURE" | "SYNTHETIC" | "DRAHMI" | string;
export interface FxQuote {
  base: string; quote: string; rate: number; rateDate: string;
  previousRate: number | null; previousDate: string | null; changePercent: number | null;
  source: DataSource; ageDays: number; stale: boolean;
}
export interface BenchmarkSeries {
  code: string; label: string; currency: string;
  returnMadPercent: number | null; returnLocalPercent: number | null;
}
export interface BenchmarkComparison {
  range: string; start: string | null; end: string | null;
  points: { date: string; values: Record<string, number | null> }[];
  series: BenchmarkSeries[];
  usdMadChangePercent: number | null; verdict: string;
  sources: { series: string; sources: DataSource[] }[];
  sampleData: boolean; notes: string[];
}
export interface Brief {
  id: number; date: string; generatedAt: string; trigger: string;
  source: "CLAUDE" | "TEMPLATE"; model: string | null;
  headline: string; body: string; factsUsed: string[];
  facts: Record<string, unknown>; sampleData: boolean; fallbackReason: string | null;
  inputTokens: number | null; outputTokens: number | null; latencyMs: number | null;
  aiEnabled: boolean; budgetRemaining: number;
}
export interface FxApplied {
  base: string; quote: string; rate: number; rateDate: string; source: DataSource; stale: boolean;
}
export interface IndexQuote {
  code: string; name: string; value: number | null;
  changePercent: number | null; changeValue: number | null;
}
export interface Overview {
  instrumentCount: number; totalMarketCap: number;
  marketOpen: boolean; session: string; localTime: string;
}
export interface Dashboard {
  instruments: Instrument[]; indices: IndexQuote[]; overview: Overview;
}
export interface Bar {
  date: string; open: number; high: number; low: number; close: number; volume: number;
}
export interface Risk {
  realizedVolAnnualized: number | null; downsideVolAnnualized: number | null;
  maxDrawdown: number | null; var95: number | null; cvar95: number | null;
  beta: number | null; alpha: number | null; sharpe: number | null; sortino: number | null;
  totalReturn: number | null; amihudIlliquidity: number | null;
  zeroVolumeDays: number; sessions: number;
}
export interface InstrumentDetail {
  instrument: Instrument; history: Bar[]; risk: Risk;
}
export interface Sector { code: string; name: string; count: number }
export interface Quota {
  date: string; dailyLimit: number; consumed: number;
  remaining: number; upstreamRemaining: number | null;
}
export interface Portfolio { id: string; name: string; owner: string; baseCurrency: string }
export interface Position {
  ticker: string; quantity: number; costBasis: number; unitCost: number;
  lastPrice: number | null; marketValue: number | null;
  unrealizedPnl: number | null; unrealizedPnlPercent: number | null;
  realizedPnl: number; currency: string;
}
export interface MoneyDto { amount: number; currency: string }
export interface PortfolioSummary {
  totalValue: MoneyDto; totalCost: MoneyDto; unrealizedPnl: MoneyDto;
  realizedPnl: MoneyDto; unrealizedPnlPercent: number;
  weights: Record<string, number>; herfindahl: number | null;
  top5Concentration: number | null; positionCount: number;
  fx: FxApplied | null;
}
export interface TradeBody {
  ticker: string; quantity: number; price: number; fees?: number;
}
export interface AlertRule {
  id: string; ticker: string; type: string; threshold: number;
  enabled: boolean; armed: boolean; fireCount: number;
  hysteresisPct: number; cooldownSeconds: number;
}
export interface NewAlert {
  ticker: string; type: string; threshold: number;
  hysteresisPct?: number; cooldownSeconds?: number;
}
export interface AlertFiring {
  id: number; ticker: string; triggeredPrice: number;
  threshold: number; reason: string; firedAt: string;
}
export interface PriceTick {
  ticker: string; price: number; currency: string;
  sector: string; source: string; at: string;
}
