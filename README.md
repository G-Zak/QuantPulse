# QuantPulse

Event-driven market intelligence platform for the **Casablanca Stock Exchange (MASI)**.

Four Spring Boot services over a RabbitMQ backbone, backed by PostgreSQL, fronted by a
Next.js dashboard. Market data comes from the [Drahmi](https://drahmi.app) API — whose
free tier allows **100 requests per day**, a constraint that drives most of the
architecture below.

> Rebuilt from the ground up in August 2026.

---

## Architecture

```
                    Drahmi API  (100 req/day, ~15 min delayed)
                         │
                         │  quota governor · circuit breaker · L1+L2 cache
                         ▼
              ┌──────────────────────┐
              │  qp-marketdata :8081 │  sole upstream owner · schema: market
              └──────────┬───────────┘
                         │ transactional outbox
                         ▼
                   ╔═════════════╗
                   ║  RabbitMQ   ║  topic exchange · DLQ · parking lot
                   ╚══╦═══════╦══╝
                      │       │
        ┌─────────────┘       └──────────────┐
        ▼                                    ▼
┌──────────────────┐              ┌──────────────────┐
│ qp-portfolio     │              │ qp-alerts  :8083 │
│ :8082            │              │ schema: alerts   │
│ schema: portfolio│              └──────────────────┘
└──────────────────┘
        │                                    │
        └──────────────┬─────────────────────┘
                       ▼
              ┌──────────────────┐
              │  qp-api    :8080 │  BFF · JWT · SSE
              └────────┬─────────┘
                       ▼
              ┌──────────────────┐
              │  qp-web    :3002 │  Next.js
              └──────────────────┘

              ┌──────────────────┐
              │  qp-quant  :8084 │  Python · FastAPI · NumPy/scikit-learn
              └──────────────────┘  PCA · clustering · Monte Carlo · optimisation
                       ▲
                       └── reads OHLCV over qp-marketdata's HTTP API
```

**Service boundaries.** `qp-marketdata` is the only component that talks to the
rate-limited upstream and the only writer to market data — one place to spend a request
means one place to enforce a budget. `qp-portfolio` owns money arithmetic and
transactional integrity. `qp-alerts` is stream processing. `qp-api` is the only thing the
browser can reach.

**Database isolation.** One Postgres instance, one schema and login role per service,
no cross-schema grants. `qp_portfolio` selecting from `market.*` fails with
`permission denied` — the shared-database anti-pattern is impossible by construction.
See [ADR-002](docs/decisions/ADR-002-schema-per-service.md).

---

## The constraint

100 API calls per day, resetting 00:00 UTC. The design turns on one observation:

> `GET /stocks?limit=200` returns **all 81 listed instruments in a single call**.

A full market snapshot therefore costs 1 request, not 81.

| Job | Frequency | Calls/day |
|---|---|---|
| `/market/status` | 2× daily | 2 |
| `/stocks?limit=200` — whole market | every 15 min during the session | 24 |
| `/indices` — all indices | every 15 min during the session | 24 |
| EOD history backfill | priority queue, N tickers/day | ~15 |
| | **total** | **~65** |

Development and tests spend **zero** — everything runs off 36 recorded fixtures in
`ops/fixtures/`. See [ADR-003](docs/decisions/ADR-003-recorded-fixtures.md).

---

## Stack

| Concern | Choice |
|---|---|
| Runtime | Java 21 (LTS), Spring Boot 3.5.3 |
| Build | Maven multi-module reactor, imported BOMs ([ADR-001](docs/decisions/ADR-001-maven-reactor.md)) |
| Persistence | PostgreSQL 16, Flyway, Spring Data JPA, monthly-partitioned OHLCV |
| Messaging | RabbitMQ 3.13 via Spring AMQP — topology declared explicitly, no Spring Cloud Stream |
| Resilience | Resilience4j 2.3 — circuit breaker, retry with jitter, bulkhead, rate limiter, time limiter |
| Caching | Caffeine (L1) + Redis (L2), TTLs matched to upstream data delay |
| Scheduling | Spring `@Scheduled` + ShedLock, market-hours aware (`Africa/Casablanca`) |
| Observability | Micrometer → Prometheus → Grafana |
| Testing | JUnit 5, Testcontainers 1.21, WireMock |
| Analytics | Python 3.13, FastAPI, NumPy, pandas, SciPy, scikit-learn ([ADR-004](docs/decisions/ADR-004-python-analytics-service.md)) |
| Frontend | Next.js 16, Recharts |

---

## Running it

```bash
cp .env.example .env          # the defaults are enough — see note below
cd ops && docker compose --env-file ../.env up -d
```

`DRAHMI_API_KEY` and `ALPHA_API_KEY` are only read under the `live` Spring profile.
Everything below runs on the default `dev` profile, which serves the 36 recorded
fixtures instead — no key, no live calls, no quota spent. Once `qp-web` is up, log in
with the seeded account: `zakaria` / `quantpulse` (`DEMO_USER` / `DEMO_PASSWORD` in
`.env` to change it).

| Service | URL |
|---|---|
| Dashboard | [3002](http://localhost:3002) |
| Postgres | `localhost:5434` |
| RabbitMQ | `localhost:5672` · UI [15672](http://localhost:15672) |
| Redis | `localhost:6380` |
| Prometheus | [9090](http://localhost:9090) |
| Grafana | [3001](http://localhost:3001) |

Host ports avoid this machine's existing Postgres (5432) and Redis (6379).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B verify                 # zero live API calls
```

Analytics service and its tests:

```bash
cd qp-quant && python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt
./.venv/bin/python -m pytest tests/ -q
./.venv/bin/python -m uvicorn app.main:app --port 8084
```

Everything in containers:

```bash
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml --env-file ../.env up -d --build
```

---

## Documentation

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | architecture: diagrams, tech stack, data flows |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | how to run it, with health checks and troubleshooting |
| [`docs/decisions/`](docs/decisions/) | design decisions (ADRs) |
| [`notebooks/`](notebooks/) | executed market-analysis notebook |

---

## Quantitative analysis

`qp-quant` computes, on demand and without caching:

- **Correlation structure** — pairwise correlation reordered by hierarchical clustering, so
  blocks are visible. The clustering never sees sector labels, which makes any agreement
  with the official classification evidence rather than assumption.
- **Factor structure** — PCA on the return matrix. PC1 explaining ~48% of variance with
  uniformly-signed loadings is the signature of a genuine market factor, and sets a floor on
  how much diversification is available inside this exchange.
- **Sector decomposition** — each sector treated as an equal-weighted portfolio, not as an
  average of its members' statistics (which would overstate sector risk).
- **Distributional forecasting** — Monte Carlo under GBM, returning percentile bands and
  outcome probabilities, **never a point prediction**.
- **Calibration backtesting** — walk-forward, out-of-sample. Reports whether the model's own
  intervals are honest. See [ADR-005](docs/decisions/ADR-005-forecasting-honesty.md).
- **Portfolio optimisation** — Markowitz frontier, with the estimation-error caveat stated
  rather than glossed.
- **Anomaly detection** — modified z-score on median/MAD, robust to the outliers it is
  detecting.

The calibration backtest found a real defect in the first implementation (equal-weighted
volatility produced 90% intervals containing 100% of outcomes) and drove the switch to EWMA.
Residual overconfidence on the most volatile instrument is reported, not hidden.

## A note on the data

Drahmi is an independent market-intelligence layer, not an exchange feed. Data is
delayed and derived. This project is an internal dashboard and a learning exercise —
it is not investment advice, and it does not redistribute the data.
