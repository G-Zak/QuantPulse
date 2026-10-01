# QuantPulse — Architecture Reference

The complete high-level picture: what the system is, what it's built from, and how data
moves through it. Every diagram here reflects code that runs — the numbers were read out
of the running system, not estimated.

This is the **map**. Detail on each component comes later; where a section is deliberately
shallow it says so and points at what will cover it.

**Companion documents**
| | |
|---|---|
| [`decisions/`](decisions/) | design decisions (ADRs) |
| [`RUNBOOK.md`](RUNBOOK.md) | how to run it |

---

## 1. What this is, in one paragraph

QuantPulse ingests market data for the **81 instruments listed on the Casablanca Stock
Exchange**, stores it, and builds four things on top: a live market dashboard, a portfolio
with cost-basis and P&L accounting, a rule-based alerting engine, and a quantitative
analytics layer covering correlation structure, factor analysis, distributional forecasting
and dividend-income projection. Five backend services communicate over RabbitMQ and HTTP;
one Next.js app is the only thing a browser touches.

**The constraint that shaped everything:** the upstream data vendor allows **100 API calls
per day**. Not per minute — per day. That single number drove the quota governor, the
caching strategy, the market-hours scheduler, the priority backfill queue, and the decision
to record fixtures rather than call the API during development.

---

## 2. System context

Who and what the system talks to.

```mermaid
flowchart TB
    user["👤 Investor / Analyst<br/><i>browser</i>"]
    dev["👤 Developer<br/><i>local machine</i>"]

    subgraph qp["QuantPulse"]
        direction TB
        core["Market intelligence platform<br/>ingest · store · analyse · serve"]
    end

    drahmi["🌐 Drahmi API<br/><b>100 requests / day</b><br/>~15 min delayed quotes<br/>T+1D history"]
    fixtures[("📁 Recorded fixtures<br/>36 real responses<br/>captured once")]

    user -->|"HTTPS · JWT"| qp
    dev -->|"dev profile"| qp
    qp -->|"live profile only<br/>quota-governed"| drahmi
    qp -->|"dev + test profiles<br/><b>zero API cost</b>"| fixtures
    drahmi -.->|"recorded once<br/>2026-08-03"| fixtures

    style drahmi fill:#3a2a1a,stroke:#d4a12a,color:#e6edf6
    style fixtures fill:#1a2a2a,stroke:#26a37b,color:#e6edf6
    style qp fill:#131822,stroke:#232c3d,color:#e6edf6
```

The dashed line matters: fixtures were captured from the live API **once**, deliberately,
and every subsequent development and test run reads them instead. See
[ADR-003](decisions/ADR-003-recorded-fixtures.md).

---

## 3. Container diagram

Every deployable process and datastore.

```mermaid
flowchart TB
    browser["🌐 Browser"]

    subgraph edge["Edge — the only publicly reachable surface"]
        web["<b>qp-web</b> :3002<br/>Next.js 16 · React 19<br/>5 screens"]
        api["<b>qp-api</b> :8080<br/>Spring Boot · BFF<br/>JWT · CORS · SSE · aggregation"]
    end

    subgraph internal["Internal services — never exposed to the browser"]
        md["<b>qp-marketdata</b> :8081<br/>upstream ACL · quota governor<br/>scheduler · outbox producer"]
        pf["<b>qp-portfolio</b> :8082<br/>trade ledger · cost basis<br/>P&L · risk metrics"]
        al["<b>qp-alerts</b> :8083<br/>rule engine · hysteresis<br/>notification outbox"]
        qt["<b>qp-quant</b> :8084<br/>Python · FastAPI<br/>PCA · Monte Carlo · optimisation"]
    end

    subgraph infra["Infrastructure"]
        pg[("<b>PostgreSQL 16</b> :5434<br/>3 isolated schemas<br/>48 OHLCV partitions")]
        mq{{"<b>RabbitMQ 3.13</b> :5672<br/>topic exchange<br/>DLQ · retry · parking lot"}}
        redis[("<b>Redis 7</b> :6380<br/>L2 cache<br/>no persistence")]
        prom["<b>Prometheus</b> :9090"]
        graf["<b>Grafana</b> :3001"]
    end

    drahmi["🌐 Drahmi API"]

    browser -->|"HTTPS"| web
    web -->|"REST + SSE"| api

    api -->|"HTTP"| md
    api -->|"HTTP"| pf
    api -->|"HTTP"| al
    api -->|"HTTP · 90s timeout"| qt

    md -->|"quota-governed"| drahmi
    md -->|"writes"| pg
    md -->|"L1 Caffeine + L2"| redis
    md -->|"publishes via outbox"| mq

    mq -->|"price.tick.#"| pf
    mq -->|"price.tick.#"| al
    mq -->|"price.tick.#"| api

    pf -->|"writes"| pg
    al -->|"writes"| pg
    pf -->|"reads history over HTTP"| md
    qt -->|"reads history over HTTP"| md

    md -.->|"/actuator/prometheus"| prom
    pf -.-> prom
    al -.-> prom
    api -.-> prom
    prom --> graf

    style edge fill:#1a2130,stroke:#d4a12a,color:#e6edf6
    style internal fill:#131822,stroke:#232c3d,color:#e6edf6
    style infra fill:#0f1520,stroke:#232c3d,color:#e6edf6
    style drahmi fill:#3a2a1a,stroke:#d4a12a,color:#e6edf6
```

### Why the boundaries fall where they do

| Service | Owns | Rationale |
|---|---|---|
| **qp-marketdata** | The Drahmi integration and all market data | Only component that can spend an API call, so there is exactly **one** place to enforce a budget. Also the sole writer to the `market` schema — no other service can corrupt it. |
| **qp-portfolio** | Money | Everything requiring exact decimal arithmetic and transactional integrity lives here, behind one boundary. |
| **qp-alerts** | Stream processing | Pure consumer of the price stream. Stateless with respect to market data; holds only rules and firing history. |
| **qp-quant** | Numerical analysis | Vectorised linear algebra belongs in NumPy/SciPy, not hand-written Java ([ADR-004](decisions/ADR-004-python-analytics-service.md)). Its seconds-of-CPU workload must not occupy a request thread in an ingestion service. |
| **qp-api** | The browser contract | Authentication, CORS and aggregation in one place instead of four. |

**Note `qp-portfolio → qp-marketdata` is an HTTP arrow, not a database arrow.** It reads
OHLCV over the public API because it physically cannot read the `market` schema — the
`qp_portfolio` role has no grant on it. That's [ADR-002](decisions/ADR-002-schema-per-service.md)
made real:

```
qp_portfolio=> SELECT * FROM market.instrument;
ERROR:  permission denied for schema market
```

---

## 4. Tech stack

### Runtime and build

| Concern | Choice | Why this and not the obvious alternative |
|---|---|---|
| Language (services) | **Java 21 LTS** | Current LTS; records, pattern matching, virtual threads available |
| Framework | **Spring Boot 3.5.3** | The stack banks actually run |
| Build | **Maven multi-module reactor** | Imports Boot's BOM rather than inheriting its parent, so the single `<parent>` slot stays free for a company POM ([ADR-001](decisions/ADR-001-maven-reactor.md)) |
| Language (analytics) | **Python 3.13** | The work is eigendecomposition and Monte Carlo; NumPy/SciPy do it correctly and at BLAS speed |
| Analytics framework | **FastAPI + Uvicorn** | Async I/O for concurrent history fetches; automatic OpenAPI |
| Frontend | **Next.js 16, React 19, Recharts 3** | App Router, server components where useful, client components where interactive |

### Data

| Concern | Choice | Notes |
|---|---|---|
| Database | **PostgreSQL 16** | One instance, three schemas, three login roles, no cross-schema grants |
| Migrations | **Flyway** | Versioned per service; `ddl-auto: validate` so Hibernate checks but never mutates |
| ORM | **Spring Data JPA / Hibernate 6.6** | Bypassed deliberately for bulk writes — see `OhlcvBatchWriter` |
| Time series | **Range partitioning by month** | 48 partitions; BRIN on the time column, B-tree on `(ticker, session_date DESC)` |
| Money type | **`NUMERIC(19,6)`** everywhere | Never `double`. `HALF_EVEN` rounding. A `Money` value type refuses to mix currencies |

### Messaging

| Concern | Choice | Notes |
|---|---|---|
| Broker | **RabbitMQ 3.13** | Topic exchange, hierarchical routing keys |
| Client | **Spring AMQP, declared by hand** | *Not* Spring Cloud Stream — it generated the topology and hid exactly the mechanics worth understanding |
| Delivery | **At-least-once + idempotent consumers** | Exactly-once *effects*, not exactly-once delivery. The distinction matters |
| Reliability | **Transactional outbox** | DB write and event publish commit together; a relay drains it |
| Failure path | **DLX → TTL retry queue → parking lot** | Broker-side delayed retry; no consumer thread blocks on a sleep |

### Resilience and operations

| Concern | Choice | Notes |
|---|---|---|
| Circuit breaking | **Resilience4j 2.3** | Breaker, retry with jitter, time limiter. Quota denial is *excluded* from breaker failures — it's our decision, not an upstream fault |
| Caching | **Caffeine (L1) + Redis (L2)** | TTL matched to the vendor's 15-minute delay. The cache is part of the budget, not an optimisation |
| Scheduling | **`@Scheduled` + ShedLock** | Two instances double-firing wouldn't duplicate work — it would exhaust the daily budget by lunchtime |
| Metrics | **Micrometer → Prometheus → Grafana** | Custom gauges for quota remaining, outbox backlog, SSE subscribers |
| Auth | **Spring Security + JJWT 0.12** | Stateless; CSRF correctly disabled *because* auth is a bearer token |
| Containers | **Docker Compose** | Multi-stage builds, non-root users, `MaxRAMPercentage` for cgroup-aware heap sizing |

### Testing

| Layer | Tooling | Count |
|---|---|---|
| Money & cost basis | JUnit 5 + AssertJ | 25 Java tests |
| Analytics core | pytest | 45 Python tests |
| Integration | Testcontainers + WireMock | *not done yet* |

---

## 5. Workflows

### 5.1 Ingestion cycle — the core loop

Fires every 15 minutes during the Casablanca session. **One API call refreshes all 81
instruments.**

```mermaid
sequenceDiagram
    autonumber
    participant S as IngestionScheduler
    participant C as TradingCalendar
    participant Q as QuotaGovernor
    participant P as MarketDataProvider
    participant D as Drahmi API
    participant DB as PostgreSQL
    participant O as OutboxRelay
    participant MQ as RabbitMQ

    Note over S: @Scheduled every 15 min<br/>@SchedulerLock prevents double-fire

    S->>C: isTradingNow?
    alt market closed
        C-->>S: false
        Note over S: exit — no call spent<br/>on prices that cannot move
    else market open
        C-->>S: true
        S->>P: fetchMarketSnapshot()
        P->>Q: tryAcquire("market-snapshot", NORMAL, 1)

        alt remaining - 1 < floor(NORMAL) = 15
            Q-->>P: denied
            P-->>S: empty list — degrade to stored data
        else budget available
            Q->>DB: SELECT ... FOR UPDATE on quota_budget
            Q->>DB: consumed += 1
            Q-->>P: granted
            P->>D: GET /stocks?limit=200
            D-->>P: 81 instruments · X-RateLimit-Remaining
            P->>Q: recordOutcome(remaining) — reconcile
        end

        Note over S,DB: ── one transaction ──
        S->>DB: upsert 81 instruments
        loop only where price actually changed
            S->>DB: INSERT outbox_event
        end
        Note over S,DB: ── commit ──
    end

    Note over O: separate poller, every 1s
    O->>DB: SELECT ... FOR UPDATE SKIP LOCKED
    O->>MQ: publish price.tick.{TICKER}
    O->>DB: mark published_at
```

Two details worth noticing:

- **Events are only written when a price actually moved.** Republishing 81 unchanged prices
  every cycle would be pure noise and force every consumer to filter.
- **The outbox row is written in the same transaction as the instrument update.** That's the
  dual-write problem solved — either both land or neither does.

### 5.2 Event fan-out — one publish, three independent consumers

```mermaid
sequenceDiagram
    autonumber
    participant O as OutboxRelay
    participant X as quantpulse.market
    participant PV as portfolio.valuation
    participant AE as alerts.evaluation
    participant PS as api.price-stream
    participant DB as PostgreSQL
    participant B as Browser

    O->>X: publish "price.tick.ATW"<br/>persistent · mandatory · confirms

    par independent copies — different queue names
        X->>PV: routing key matches "price.tick.#"
        PV->>DB: INSERT processed_event (PK = event_id)
        alt duplicate delivery
            DB-->>PV: unique violation
            Note over PV: already handled — ack and skip
        else first delivery
            PV->>DB: revalue every position holding ATW
            Note over PV: rejects ticks older than<br/>the price already held
        end
    and
        X->>AE: same key, different queue
        AE->>DB: dedupe on event_id
        AE->>DB: evaluate rules · hysteresis · cooldown
    and
        X->>PS: non-durable, auto-delete queue
        PS->>B: SSE event, filtered server-side
    end
```

**Same routing key, three different queue names → three full copies.** Had they shared a
queue name they'd be *competing* consumers splitting the stream — same broker, opposite
semantics. That distinction is worth being able to state precisely.

The API's queue is deliberately non-durable: a missed tick there is superseded seconds
later, whereas a missed tick in valuation means a stale position.

### 5.3 The quota decision

Every upstream call passes this gate before a socket opens.

```mermaid
flowchart TB
    start(["Job requests 1 API call"]) --> prio{"Job priority?"}

    prio -->|"CRITICAL<br/>health probes"| f0["floor = 0"]
    prio -->|"HIGH<br/>index refresh"| f5["floor = 5"]
    prio -->|"NORMAL<br/>market snapshot"| f15["floor = 15"]
    prio -->|"BACKFILL<br/>history, dividends"| f35["floor = 35"]

    f0 --> check{"remaining - 1 >= floor?"}
    f5 --> check
    f15 --> check
    f35 --> check

    check -->|no| deny["Denied<br/>QuotaExhaustedException"]
    check -->|yes| lock["SELECT ... FOR UPDATE<br/>on quota_budget"]

    lock --> spend["consumed += 1<br/>REQUIRES_NEW commit"]
    spend --> http_call["HTTP request"]
    http_call --> rec["Read X-RateLimit-Remaining<br/>reconcile pessimistically"]
    rec --> done(["Data returned"])

    deny --> degrade["Serve stored data<br/>circuit breaker does NOT open"]

    style deny fill:#3a1a1a,stroke:#e0524c,color:#e6edf6
    style done fill:#1a3a2a,stroke:#26a37b,color:#e6edf6
    style degrade fill:#3a2a1a,stroke:#d4a12a,color:#e6edf6
```

Three decisions embedded here:

1. **Priority floors** stop a backfill loop from starving the next market snapshot. A naive
   `remaining > 0` check would allow exactly that.
2. **`REQUIRES_NEW`** commits the spend independently of the caller's transaction. Once the
   HTTP request leaves, the quota is gone whether or not the ingestion later rolls back.
3. **Quota denial is excluded from circuit-breaker failures.** It's our own decision, not an
   upstream fault; counting it would open the breaker every time the budget ran low.

### 5.4 Dashboard load — BFF aggregation

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant API as qp-api
    participant MD as qp-marketdata

    B->>API: GET /api/v1/market/dashboard

    par three concurrent calls — latency is the slowest, not the sum
        API->>MD: GET /instruments
    and
        API->>MD: GET /indices
    and
        API->>MD: GET /overview
    end

    Note over API: CompletableFuture.allOf().join()<br/>each section degrades independently

    API-->>B: { instruments[81], indices[2], overview }

    B->>API: GET /api/v1/stream?tickers=ATW,IAM
    activate API
    Note over API,B: SSE stays open, server-side filter means<br/>3 events delivered, not 75
    deactivate API
```

Sequentially this is three round trips of latency; concurrently it's one. And every upstream
call reads **our** database — a dashboard refresh costs zero Drahmi calls, which it must,
or quota consumption would scale with how many browser tabs are open.

### 5.5 Analysis request

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant API as qp-api
    participant QT as qp-quant
    participant MD as qp-marketdata

    B->>API: GET /analysis/market-report?limit=30
    API->>QT: proxy · 90s read timeout · HTTP/1.1 pinned

    QT->>MD: GET /instruments
    par 30 concurrent history fetches
        QT->>MD: GET /instruments/{t}/history
    end

    Note over QT: align on session date, never by array index
    QT->>QT: log returns → correlation matrix
    QT->>QT: hierarchical clustering (Ward)
    QT->>QT: PCA → explained variance
    QT->>QT: sector decomposition

    QT-->>API: correlation + factors + sectors
    API-->>B: one aggregate response
```

The **90-second read timeout** on the quant client is not cosmetic: a 40×40 correlation
matrix plus a walk-forward backtest is seconds of CPU, and the 5-second timeout used for the
Java services would abort every request.

The **HTTP/1.1 pin** is a real bug fix — see §9.

### 5.6 Dividend income projection

The workflow you're most likely to be asked to walk through, because the analysis is the
interesting part rather than the arithmetic.

```mermaid
flowchart TB
    in(["Investment amount + currency + ticker"]) --> fx{"currency = MAD?"}
    fx -->|no| rate["Apply FX rate<br/>caller-supplied or indicative"]
    fx -->|yes| shares
    rate --> shares["amountMAD ÷ price<br/><b>floor to whole shares</b>"]

    shares --> ttm["Trailing-12-month dividend/share<br/><i>computed from primary data,<br/>not the vendor's yield field</i>"]

    ttm --> y["yield = TTM ÷ price"]

    y --> analysis["── Analysis ──"]

    analysis --> hist["Dividend record<br/>CAGR · cuts characterised<br/>worst cut · recovered?"]
    analysis --> payout["Payout ratio<br/>EPS = price ÷ PE<br/>unavailable if PE missing"]
    analysis --> price1y["1-year price change"]

    hist --> trap{"Yield-trap check<br/>4 independent signals"}
    payout --> trap
    price1y --> trap
    y --> trap

    trap -->|"≥2 signals"| high["🔴 high"]
    trap -->|"1 signal"| mod["🟠 moderate"]
    trap -->|"none"| low["🟢 low"]

    high --> scen
    mod --> scen
    low --> scen

    scen["Three scenarios<br/>flat · historical CAGR · 30% cut"] --> out(["Projection + risks + disclaimer"])

    style trap fill:#3a2a1a,stroke:#d4a12a,color:#e6edf6
    style high fill:#3a1a1a,stroke:#e0524c,color:#e6edf6
    style low fill:#1a3a2a,stroke:#26a37b,color:#e6edf6
```

Why it's built this way: **a high yield is usually bad news.** Yield is dividend ÷ price, so
a halved price with an uncut dividend doubles the yield. Screening on yield alone
systematically selects the companies least able to keep paying. The screen proves it on real
data — MNG yields 5.55% while paying out **140% of earnings**.

### 5.7 Backfill queue — spreading expensive work across days

A year of history for 81 instruments costs 81 calls. That doesn't fit in a 100-call day
alongside everything else, so it becomes a durable priority queue.

```mermaid
stateDiagram-v2
    [*] --> PENDING: seeded, priority = market-cap rank

    PENDING --> RUNNING: scheduler picks top N<br/>(3 per night)
    RUNNING --> DONE: data written
    RUNNING --> PENDING: transient failure<br/>attempts < 3
    RUNNING --> FAILED: attempts ≥ 3
    DONE --> [*]
    FAILED --> [*]: needs inspection

    note right of PENDING
        Three kinds share one queue
        and one budget:
        OHLCV · DIVIDEND · FUNDAMENTALS
    end note

    note right of RUNNING
        BACKFILL priority — may only
        spend while ≥35 calls remain,
        so it never starves a snapshot
    end note
```

---

## 6. Data model

Three schemas, isolated by PostgreSQL role grants.

### `market` — owned by qp-marketdata

```mermaid
erDiagram
    SECTOR ||--o{ INSTRUMENT : classifies
    INSTRUMENT ||--o{ OHLCV_BAR : "price history"
    INSTRUMENT ||--o{ DIVIDEND : "declares"
    INSTRUMENT ||--o{ BACKFILL_TASK : "queued work"
    MARKET_INDEX ||--o{ INDEX_HISTORY : "daily closes"

    SECTOR {
        bigint id PK
        varchar code UK "BANQU, TÉLÉC — 25, French, accented"
        varchar name
        int instrument_count
    }
    INSTRUMENT {
        bigint id PK "surrogate — tickers can change"
        varchar ticker UK
        varchar isin
        varchar sector_code FK
        numeric last_price "NUMERIC(19,6)"
        numeric market_cap
        numeric pe_ratio "nullable — needs FUNDAMENTALS backfill"
        numeric beta "vendor returns null; we compute our own"
        bigint version "optimistic lock"
    }
    OHLCV_BAR {
        varchar ticker PK "48 monthly partitions"
        date session_date PK "partition key"
        numeric open
        numeric high
        numeric low
        numeric close
        bigint volume "zero is legitimate — 26 of 245 sessions"
    }
    DIVIDEND {
        bigint id PK
        varchar ticker UK "unique with ex_date"
        date ex_date UK
        date payment_date
        numeric amount "per share"
    }
    OUTBOX_EVENT {
        bigint id PK
        uuid event_id UK "consumer dedup key"
        varchar routing_key
        jsonb payload
        timestamptz published_at "NULL = pending"
    }
    QUOTA_BUDGET {
        date quota_date PK "UTC — matches vendor reset"
        int daily_limit
        int consumed
        int upstream_remaining "reconciliation"
    }
    BACKFILL_TASK {
        bigint id PK
        varchar ticker
        varchar kind "OHLCV | DIVIDEND | FUNDAMENTALS"
        int priority "market-cap rank"
        varchar status
    }
```

### `portfolio` — owned by qp-portfolio

```mermaid
erDiagram
    PORTFOLIO ||--o{ TRADE_TRANSACTION : "append-only ledger"
    PORTFOLIO ||--o{ POSITION : "derived state"
    PORTFOLIO ||--o{ TAX_LOT : "FIFO lots"

    PORTFOLIO {
        uuid id PK
        varchar owner
        varchar name
        varchar base_currency
    }
    TRADE_TRANSACTION {
        bigint id PK "IMMUTABLE — no setters"
        uuid portfolio_id FK
        varchar type "BUY SELL DIVIDEND SPLIT"
        numeric quantity "NUMERIC — splits make fractions"
        numeric price "NUMERIC(19,6)"
        numeric fees
        timestamptz executed_at "may be backdated"
        timestamptz recorded_at "when we learned"
    }
    POSITION {
        bigint id PK "a fold over the ledger"
        varchar ticker UK
        numeric quantity
        numeric cost_basis "fees included"
        numeric realized_pnl
        numeric last_price "from the event stream"
        bigint version "optimistic lock"
    }
    TAX_LOT {
        bigint id PK "only needed for FIFO"
        timestamptz acquired_at
        numeric remaining_quantity
        numeric unit_cost "fees folded in"
    }
    PROCESSED_EVENT {
        uuid event_id PK "idempotency guard"
        varchar consumer
    }
```

The ledger is **append-only by design**: a correction is a compensating row, never an
`UPDATE`. That makes any historical position reconstructible by replay — which doubles as
the reconciliation tool (`POST /{id}/positions/{ticker}/rebuild`).

### `alerts` — owned by qp-alerts

```mermaid
erDiagram
    ALERT_RULE ||--o{ ALERT_FIRING : "fires"
    ALERT_FIRING ||--o{ NOTIFICATION_OUTBOX : "queues"

    ALERT_RULE {
        uuid id PK
        varchar ticker
        varchar type "PRICE_ABOVE PRICE_BELOW PERCENT_MOVE_UP/DOWN DRAWDOWN"
        numeric threshold
        numeric hysteresis_pct "re-arm margin"
        int cooldown_seconds "time floor"
        boolean armed "edge-triggered, not level-triggered"
        int fire_count
    }
    ALERT_FIRING {
        bigint id PK
        numeric triggered_price
        text reason "captured at firing time"
        timestamptz fired_at
    }
    NOTIFICATION_OUTBOX {
        bigint id PK
        varchar channel
        jsonb payload
        timestamptz delivered_at "NULL = pending"
        int attempts
    }
```

---

## 7. Messaging topology

```mermaid
flowchart LR
    prod["qp-marketdata<br/>OutboxRelay"]

    subgraph exch["quantpulse.market — topic exchange, durable"]
        rk1["price.tick.{TICKER}"]
        rk2["ohlcv.bar.{TICKER}"]
        rk3["index.tick.{CODE}"]
    end

    subgraph queues["Consumer queues — one per (service, concern)"]
        q1["portfolio.valuation<br/>durable"]
        q2["alerts.evaluation<br/>durable"]
        q3["api.price-stream.{uuid}<br/>non-durable · auto-delete"]
    end

    subgraph failure["Failure path"]
        r1["portfolio.valuation.retry<br/>TTL 10s, no consumer"]
        r2["alerts.evaluation.retry<br/>TTL 10s, no consumer"]
        dlx["quantpulse.market.dlx"]
        pl["quantpulse.parking-lot<br/>nothing drains it automatically"]
    end

    prod --> exch
    rk1 -->|"price.tick.#"| q1
    rk1 -->|"price.tick.#"| q2
    rk1 -->|"price.tick.#"| q3

    q1 -.->|"reject, no requeue"| r1
    q2 -.->|"reject, no requeue"| r2
    r1 -.->|"TTL expiry dead-letters back"| q1
    r2 -.->|"TTL expiry dead-letters back"| q2
    r1 -.->|"after N attempts"| dlx
    r2 -.->|"after N attempts"| dlx
    dlx --> pl

    style exch fill:#1a2130,stroke:#d4a12a,color:#e6edf6
    style failure fill:#2a1a1a,stroke:#e0524c,color:#e6edf6
```

The retry queue **has no consumer**. Messages sit there until their TTL expires, at which
point RabbitMQ dead-letters them *back* to the main queue. That's the standard AMQP
delayed-retry trick — a broker-side sleep costing no consumer thread, unlike blocking in the
listener.

Nothing drains the parking lot automatically. An auto-draining parking lot is just a slower
infinite retry loop; a human inspects it and calls the replay endpoint.

---

## 8. Deployment topology

```mermaid
flowchart TB
    subgraph host["Developer machine / single host"]
        subgraph appc["Application containers"]
            a1["qp-marketdata :8081"]
            a2["qp-portfolio :8082"]
            a3["qp-alerts :8083"]
            a4["qp-api :8080"]
            a5["qp-quant :8084"]
            a6["qp-web :3002"]
        end
        subgraph infrac["Infrastructure containers"]
            i1[("postgres :5434")]
            i2{{"rabbitmq :5672 / :15672"}}
            i3[("redis :6380")]
            i4["prometheus :9090"]
            i5["grafana :3001"]
        end
    end

    appc --> infrac
    style appc fill:#131822,stroke:#d4a12a,color:#e6edf6
    style infrac fill:#0f1520,stroke:#232c3d,color:#e6edf6
```

Host ports are deliberately shifted — this machine already runs a Postgres on 5432, a Redis
on 6379, and another project on 3000. Container-internal ports stay standard.

```bash
# Infrastructure only — services from an IDE
cd ops && docker compose --env-file ../.env up -d

# Everything containerised
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml \
                         --env-file ../.env up -d --build
```

Java images are multi-stage (Maven build → JRE-alpine runtime), run as a non-root user, and
size the heap with `-XX:MaxRAMPercentage=70` so the JVM respects the cgroup limit rather than
host memory.

---

## 9. Scheduled work — a day in the life

```mermaid
gantt
    title Daily schedule — times in Africa/Casablanca
    dateFormat HH:mm
    axisFormat %H:%M

    section Overnight
    Nightly job — sectors, market caps, backfill x3   :done, n1, 01:15, 15m

    section Session 09:30–15:30
    Market snapshot + indices, every 15 min           :active, s1, 09:30, 360m

    section Close
    Closing snapshot                                  :crit, c1, 15:45, 10m

    section Continuous
    Outbox relay — every 1s                           :o1, 00:00, 1440m
    Notification dispatch — every 2s                  :o2, 00:00, 1440m
    Quota gauge refresh — every 60s                   :o3, 00:00, 1440m
```

### The budget that makes 100/day work

| Job | Frequency | Calls/day |
|---|---:|---:|
| `/market/status` | 2× daily | 2 |
| `/stocks?limit=200` — **all 81 instruments** | every 15 min, 6h session | 24 |
| `/indices` — all indices | every 15 min during session | 24 |
| `/sectors` | once daily | 1 |
| Movers (market-cap harvest) | once daily, 3 endpoints | 3 |
| Backfill — OHLCV / dividends / fundamentals | 3 per night | 3 |
| | **total** | **≈ 57** |

Leaving ~43 for on-demand use. **Polling every 15 minutes rather than every 5 is not
arbitrary** — the vendor's data is itself delayed ~15 minutes, so a faster poll spends budget
to receive bytes that cannot have changed.

Development and tests spend **zero**.

---

## 10. Failure handling

What happens when each dependency dies.

| Failure | Behaviour | Recovery |
|---|---|---|
| **Drahmi unreachable** | Circuit breaker opens after 50% failures over 10 calls; fallbacks return empty | Half-open after 60s. Dashboard serves stored data throughout |
| **Quota exhausted** | `tryAcquire` denies before a socket opens. Breaker does *not* open | Budget resets 00:00 UTC |
| **RabbitMQ down** | Outbox rows accumulate with `published_at IS NULL`; relay logs and retries | Backlog drains automatically on reconnect. **No events lost** — that's the point of the outbox |
| **Consumer throws** | Rejected without requeue → retry queue → 10s TTL → back to main queue | After N attempts → parking lot, awaiting manual replay |
| **Duplicate delivery** | `processed_event` PK violation caught; ack and skip | Automatic |
| **Out-of-order tick** | `Position.applyMarketPrice` rejects anything older than the held price | Automatic — consumer is order-insensitive by construction |
| **Concurrent position write** | `@Version` optimistic lock → `OptimisticLockingFailureException` | Message redelivered, retry sees fresh state |
| **qp-quant down** | BFF `safeMap` returns empty; analysis panels degrade | Market, portfolio and alerts pages unaffected |
| **Postgres down** | Services fail readiness; Hikari fails fast at 5s rather than queueing | Manual |

### A real bug worth knowing

Every POST from qp-api to qp-quant arrived with a **zero-length body**. The JDK
`HttpClient` defaults to attempting an HTTP/2 cleartext upgrade (`Upgrade: h2c`); Uvicorn
speaks HTTP/1.1 only, answers as 1.1, and the body is lost in the exchange. Tomcat tolerates
the upgrade preamble — which is exactly why the *identical* helper worked against all four
Java services and only the Python one broke.

Nothing in the Java code looked wrong. It was only visible by dumping the headers Uvicorn
actually received. Both clients now pin `HttpClient.Version.HTTP_1_1`.

---

## 11. Repository map

```
QuantPulse/
├── pom.xml                    Maven reactor — imports BOMs, does not inherit
│
├── qp-common/                 Shared contracts. No Spring dependency, not repackaged
│   └── money/Money.java       NUMERIC(19,6) · HALF_EVEN · refuses currency mixing
│   └── event/                 PriceTickEvent · OhlcvBarEvent · IndexTickEvent · Topology
│
├── qp-marketdata/     :8081   Upstream ACL + market database
│   ├── upstream/              MarketDataProvider — Http (live) | Fixture (dev/test)
│   ├── quota/QuotaGovernor    Priority floors · reconciliation · Micrometer gauge
│   ├── ingestion/             Scheduler · TradingCalendar · backfill · JDBC batch writer
│   ├── outbox/                OutboxWriter (MANDATORY) · OutboxRelay (SKIP LOCKED)
│   └── db/migration/          V1 reference · V2 partitions · V3 outbox+quota
│                              V4 shedlock · V5 dividends · V6 fundamentals
│
├── qp-portfolio/      :8082   Money
│   ├── domain/CostBasis       Weighted-average + FIFO, pure functions
│   ├── consumer/              Idempotent valuation consumer
│   └── risk/RiskCalculator    Vol · drawdown · VaR/CVaR · beta · HHI
│
├── qp-alerts/         :8083   Rule engine
│   └── domain/AlertRule       Hysteresis + cooldown live on the entity
│
├── qp-api/            :8080   BFF
│   ├── security/              JwtService · JwtAuthFilter · UserAccountService
│   ├── bff/GatewayController  Concurrent fan-out, independent degradation
│   └── stream/                SSE with server-side ticker filtering
│
├── qp-quant/          :8084   Python analytics
│   ├── app/analytics.py       Correlation · PCA · Monte Carlo · calibration · frontier
│   ├── app/income.py          Dividend projection + sustainability analysis
│   └── tests/                 45 tests, no network, no framework
│
├── qp-web/            :3002   Next.js — Market · Analysis · Income · Portfolio · Alerts
│
├── notebooks/                 Executed market-analysis notebook, 8 charts
├── ops/                       Compose · Dockerfiles · fixtures · record_fixtures.py
└── docs/                      Architecture, runbook, ADRs
```

---

## 12. Endpoint catalogue

### Public — no authentication

| Method | Path | Service | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/market/dashboard` | api | Instruments + indices + overview, one call |
| `GET` | `/api/v1/market/instruments` | api | All 81, market-cap ordered |
| `GET` | `/api/v1/market/instruments/{t}/detail` | api | Detail + history + risk |
| `GET` | `/api/v1/market/quota` | api | Live quota state |
| `GET` | `/api/v1/stream?tickers=` | api | SSE live prices |
| `GET` | `/api/v1/analysis/market-report` | quant | Correlation + PCA + sectors |
| `GET` | `/api/v1/analysis/forecast/{t}` | quant | Distribution + calibration backtest |
| `GET` | `/api/v1/analysis/income/screen` | quant | Yield ranking with quality signals |
| `POST` | `/api/v1/analysis/income` | quant | Income projection + analysis |
| `POST` | `/api/v1/analysis/optimise` | quant | Efficient frontier |
| `GET` | `/api/v1/analysis/anomalies/{t}` | quant | Modified z-score outliers |

### Authenticated — Bearer JWT

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/auth/login` | Issue token, 30-min TTL |
| `GET` | `/api/v1/portfolios` | Scoped to token subject |
| `POST` | `/api/v1/portfolios/{id}/buy` \| `/sell` | Record a trade |
| `GET` | `/api/v1/portfolios/{id}/summary` | Value, P&L, HHI concentration |
| `GET`/`POST`/`DELETE` | `/api/v1/alerts` | Manage rules |

### Operational — qp-marketdata direct

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/ops/ingest` | Trigger an ingestion cycle |
| `POST` | `/api/v1/ops/backfill/{kind}/seed` \| `/run` | Drive the backfill queue |
| `GET` | `/api/v1/ops/quota` | Budget state |

---

## 13. Data provenance — read before demoing

| Data | Status |
|---|---|
| Tickers, names, sectors, market caps, current prices | **Real** — recorded from Drahmi 2026-08-03 |
| Price history for **ATW, IAM, MNG** | **Real** — 245 sessions each |
| Dividend history for **ATW** | **Real** — 16 years, including the 48% COVID cut |
| Price and dividend history for the other 78 | **Simulated** by a factor model |

Fetching a year of bars for all 81 instruments would cost 81 of the vendor's 100 daily
requests. The simulator generates returns as `βᵢ·market(t) + γ·sector(t) + εᵢ(t)` with
volatility clustering — an earlier version drew each instrument independently and produced
PC1 explaining 4.8% of variance where a real market shows 30–60%, which made every
cross-sectional result meaningless.

**Read the cross-sectional analysis as correct methodology on data with known structure, not
as findings about the real Casablanca market.** This is stated in the notebook, the API and
the UI.

---

## 14. Where the detail lives

This document deliberately stops at the level of "what and why". Depth per component comes
next; the natural order is:

1. **Quota governor + ingestion** — the design centrepiece
2. **Transactional outbox + idempotent consumers** — the messaging guarantees
3. **Money, cost basis, and the ledger** — financial correctness
4. **Partitioning and the query plan** — the database axis
5. **Security filter chain** — how a request becomes an authenticated principal
6. **Analytics core** — the statistics and their assumptions
7. **Income analysis** — yield traps, payout, FX

Say which one to open first.

*Last updated: after the dividend income feature. All figures read from the running system.*
