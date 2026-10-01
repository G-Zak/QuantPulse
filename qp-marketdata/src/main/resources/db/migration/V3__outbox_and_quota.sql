-- ============================================================================
--  V3 — transactional outbox, API quota ledger, backfill queue
-- ============================================================================

-- ── Transactional outbox ────────────────────────────────────────────────────
-- Solves the dual-write problem. Writing to Postgres and publishing to RabbitMQ
-- are two separate systems with no shared transaction, so any ordering fails:
--
--   commit-then-publish : crash in between -> DB updated, nobody was told
--   publish-then-commit : rollback after   -> consumers act on a fact that
--                                             never became true
--
-- Instead the event row is INSERTed in the SAME transaction as the state change.
-- Either both land or neither does. A separate relay polls unpublished rows and
-- pushes them to the broker, which converts the problem from "atomicity across
-- two systems" (impossible without 2PC) into "at-least-once delivery with
-- idempotent consumers" (tractable).
CREATE TABLE outbox_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      UUID          NOT NULL,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id  VARCHAR(64)   NOT NULL,
    event_type    VARCHAR(64)   NOT NULL,
    routing_key   VARCHAR(128)  NOT NULL,

    -- JSONB, not TEXT: queryable when debugging why a consumer choked, and
    -- validated as well-formed JSON on write.
    payload       JSONB         NOT NULL,

    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INTEGER       NOT NULL DEFAULT 0,
    last_error    TEXT,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

-- Partial index on exactly the relay's query: unpublished rows, oldest first.
-- Once a row is published it drops out of the index entirely, so the index stays
-- proportional to the backlog rather than to total history.
CREATE INDEX idx_outbox_unpublished
    ON outbox_event (occurred_at)
    WHERE published_at IS NULL;

-- ── API quota ledger ────────────────────────────────────────────────────────
-- Lives in Postgres, deliberately NOT Redis. Redis in this stack runs without
-- persistence; losing the count of calls already spent today would let the
-- ingester blow through a hard external limit with no way to recover until
-- 00:00 UTC. Durability matters more than latency for a counter this consequential.
CREATE TABLE quota_budget (
    quota_date     DATE         PRIMARY KEY,
    daily_limit    INTEGER      NOT NULL,
    consumed       INTEGER      NOT NULL DEFAULT 0,

    -- Last value seen in the upstream's X-RateLimit-Remaining header. Our own
    -- count and theirs should agree; a divergence means calls were made outside
    -- this service and is worth alerting on.
    upstream_remaining INTEGER,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_quota_consumed_non_negative CHECK (consumed >= 0)
);

-- Per-call audit trail. Answers "what actually spent the budget today?"
CREATE TABLE api_call_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    quota_date   DATE         NOT NULL,
    job_name     VARCHAR(64)  NOT NULL,
    endpoint     VARCHAR(255) NOT NULL,
    status_code  INTEGER,
    duration_ms  INTEGER,
    remaining_after INTEGER,
    called_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_call_log_date ON api_call_log (quota_date, called_at DESC);

-- ── Backfill work queue ─────────────────────────────────────────────────────
-- One year of history for 81 instruments is 81 calls — more than a day's entire
-- budget. So backfill is a priority queue drained a few instruments per day,
-- highest priority first, rather than a loop that would exhaust the quota and
-- fail two-thirds of the way through.
CREATE TABLE backfill_task (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker        VARCHAR(16)  NOT NULL,
    range_code    VARCHAR(8)   NOT NULL DEFAULT '1Y',

    -- Higher runs first. Seeded from market cap so the most-traded names get
    -- history first and the dashboard is useful before the backfill completes.
    priority      INTEGER      NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts      INTEGER      NOT NULL DEFAULT 0,
    last_error    TEXT,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_backfill_ticker_range UNIQUE (ticker, range_code),
    CONSTRAINT ck_backfill_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED'))
);

CREATE INDEX idx_backfill_pending
    ON backfill_task (priority DESC, id)
    WHERE status = 'PENDING';
