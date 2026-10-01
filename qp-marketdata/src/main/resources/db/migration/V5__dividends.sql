-- ============================================================================
--  V5 — dividend history
--
--  Dividends are per-instrument fetches, one API call each, so they drain
--  through the same priority backfill queue as OHLCV rather than being fetched
--  on demand. `backfill_task` gains a `kind` so one queue serves both.
-- ============================================================================

CREATE TABLE dividend (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker        VARCHAR(16)   NOT NULL,

    -- The date that determines entitlement: buy before it and you receive the
    -- dividend, buy on or after and the seller keeps it. The price typically
    -- drops by roughly the dividend amount on this date, which is why a naive
    -- "buy before ex-date for free income" strategy does not work.
    ex_date       DATE          NOT NULL,
    payment_date  DATE,

    -- Dividend per share, not a total. NUMERIC for the same reason every other
    -- money column is.
    amount        NUMERIC(19,6) NOT NULL,
    currency      VARCHAR(3)    NOT NULL DEFAULT 'MAD',
    ingested_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- One dividend per instrument per ex-date. Makes re-running a backfill
    -- idempotent at the database level rather than in application code.
    CONSTRAINT uq_dividend UNIQUE (ticker, ex_date),
    CONSTRAINT ck_dividend_amount_positive CHECK (amount > 0)
);

-- The dominant query: one instrument's dividend history, newest first.
CREATE INDEX idx_dividend_ticker_date ON dividend (ticker, ex_date DESC);

-- Distinguishes an OHLCV backfill task from a dividend one. Defaulted so the
-- existing rows keep their meaning without a data migration.
ALTER TABLE backfill_task ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'OHLCV';
ALTER TABLE backfill_task ADD CONSTRAINT ck_backfill_kind
    CHECK (kind IN ('OHLCV', 'DIVIDEND'));

-- The unique constraint must include kind, otherwise seeding a dividend task for
-- a ticker that already has an OHLCV task would collide.
ALTER TABLE backfill_task DROP CONSTRAINT uq_backfill_ticker_range;
ALTER TABLE backfill_task ADD CONSTRAINT uq_backfill_ticker_range
    UNIQUE (ticker, range_code, kind);

DROP INDEX IF EXISTS idx_backfill_pending;
CREATE INDEX idx_backfill_pending
    ON backfill_task (kind, priority DESC, id)
    WHERE status = 'PENDING';
