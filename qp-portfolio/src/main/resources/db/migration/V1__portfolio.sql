-- ============================================================================
--  V1 — portfolio, transaction ledger, derived positions, idempotency
-- ============================================================================

CREATE TABLE portfolio (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner         VARCHAR(128)  NOT NULL,
    name          VARCHAR(128)  NOT NULL,
    base_currency VARCHAR(3)    NOT NULL DEFAULT 'MAD',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_portfolio_owner_name UNIQUE (owner, name)
);

-- ── Transaction ledger ──────────────────────────────────────────────────────
-- Append-only. A trade that happened cannot un-happen: a correction is a new
-- compensating row, never an UPDATE or DELETE of the original. This is the same
-- reason double-entry bookkeeping never erases an entry, and it is what makes
-- the ledger auditable — you can always replay it to reconstruct any position at
-- any point in time.
--
-- `position` is DERIVED state, a materialised fold over this table. If the two
-- ever disagree, the ledger wins and the position is rebuilt.
CREATE TABLE trade_transaction (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    portfolio_id  UUID          NOT NULL,
    ticker        VARCHAR(16)   NOT NULL,
    type          VARCHAR(16)   NOT NULL,

    -- Quantity is NUMERIC, not INTEGER: corporate actions produce fractional
    -- share counts (a 3-for-2 split on an odd lot), and rounding them away
    -- silently loses value.
    quantity      NUMERIC(19,6) NOT NULL,

    -- NUMERIC(19,6) with a fixed scale, matching the Money value type. Never
    -- DOUBLE PRECISION: 0.1 + 0.2 != 0.3 in binary floating point, and over a
    -- ledger of thousands of rows that error becomes a reconciliation break.
    price         NUMERIC(19,6) NOT NULL,
    fees          NUMERIC(19,6) NOT NULL DEFAULT 0,
    currency      VARCHAR(3)    NOT NULL DEFAULT 'MAD',

    executed_at   TIMESTAMPTZ   NOT NULL,
    recorded_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    note          TEXT,

    CONSTRAINT fk_txn_portfolio FOREIGN KEY (portfolio_id)
        REFERENCES portfolio (id) ON DELETE CASCADE,
    CONSTRAINT ck_txn_type CHECK (type IN ('BUY', 'SELL', 'DIVIDEND', 'SPLIT')),
    CONSTRAINT ck_txn_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_txn_price_non_negative CHECK (price >= 0),
    CONSTRAINT ck_txn_fees_non_negative  CHECK (fees >= 0)
);

-- The dominant query is "replay this portfolio's ledger in execution order",
-- which this index serves directly.
CREATE INDEX idx_txn_portfolio_ticker ON trade_transaction (portfolio_id, ticker, executed_at);

-- ── Positions (derived) ─────────────────────────────────────────────────────
CREATE TABLE position (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    portfolio_id    UUID          NOT NULL,
    ticker          VARCHAR(16)   NOT NULL,

    quantity        NUMERIC(19,6) NOT NULL DEFAULT 0,
    -- Total amount paid for the shares currently held, fees included.
    cost_basis      NUMERIC(19,6) NOT NULL DEFAULT 0,
    -- Locked in on SELL. Unrealised P&L is computed on read from the live price.
    realized_pnl    NUMERIC(19,6) NOT NULL DEFAULT 0,

    last_price      NUMERIC(19,6),
    last_price_at   TIMESTAMPTZ,
    currency        VARCHAR(3)    NOT NULL DEFAULT 'MAD',
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Optimistic lock. Two price events for the same ticker can be delivered
    -- concurrently on different consumer threads; without @Version the later
    -- write silently overwrites the earlier one (lost update).
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_position UNIQUE (portfolio_id, ticker),
    CONSTRAINT fk_position_portfolio FOREIGN KEY (portfolio_id)
        REFERENCES portfolio (id) ON DELETE CASCADE,
    CONSTRAINT ck_position_quantity_non_negative CHECK (quantity >= 0)
);

CREATE INDEX idx_position_ticker ON position (ticker);

-- ── Open tax lots, for FIFO cost basis ──────────────────────────────────────
-- Weighted-average basis needs only a running total, but FIFO needs to know
-- which specific shares are being sold, so the individual purchase lots must be
-- kept and consumed oldest-first.
CREATE TABLE tax_lot (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    portfolio_id      UUID          NOT NULL,
    ticker            VARCHAR(16)   NOT NULL,
    acquired_at       TIMESTAMPTZ   NOT NULL,
    original_quantity NUMERIC(19,6) NOT NULL,
    remaining_quantity NUMERIC(19,6) NOT NULL,
    unit_cost         NUMERIC(19,6) NOT NULL,
    currency          VARCHAR(3)    NOT NULL DEFAULT 'MAD',

    CONSTRAINT fk_lot_portfolio FOREIGN KEY (portfolio_id)
        REFERENCES portfolio (id) ON DELETE CASCADE,
    CONSTRAINT ck_lot_remaining CHECK (remaining_quantity >= 0
                                   AND remaining_quantity <= original_quantity)
);

-- Partial index on exactly the FIFO consumption query: open lots, oldest first.
CREATE INDEX idx_lot_open ON tax_lot (portfolio_id, ticker, acquired_at)
    WHERE remaining_quantity > 0;

-- ── Consumer idempotency ────────────────────────────────────────────────────
-- RabbitMQ guarantees at-least-once delivery. A consumer that crashes between
-- processing and ack will see the same message again; so will one whose publisher
-- retried after a lost confirm. Recording the event id under a UNIQUE constraint
-- makes the second delivery a duplicate-key violation the consumer can catch and
-- treat as "already handled".
--
-- The constraint is in the database rather than an in-memory Set on purpose: the
-- guarantee has to survive a restart and hold across multiple consumer instances,
-- and only a shared, durable store does that.
CREATE TABLE processed_event (
    event_id     UUID         PRIMARY KEY,
    consumer     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_event_at ON processed_event (processed_at);
