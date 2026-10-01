-- ============================================================================
--  V2 — OHLCV time series, range-partitioned by month
--
--  Why partition at all at this size?
--    81 instruments × ~245 sessions/year ≈ 20k rows/year. That needs no
--    partitioning. It is here because the *access pattern* is what partitioning
--    optimises for, and it is the pattern a real market-data store has:
--    always bounded by a date range, never "scan all history".
--
--    Partition pruning lets the planner skip whole partitions before reading a
--    single page. On a range query the difference shows up in EXPLAIN as fewer
--    scanned relations, not just fewer rows.
-- ============================================================================

CREATE TABLE ohlcv_bar (
    ticker        VARCHAR(16)   NOT NULL,
    session_date  DATE          NOT NULL,
    open          NUMERIC(19,6) NOT NULL,
    high          NUMERIC(19,6) NOT NULL,
    low           NUMERIC(19,6) NOT NULL,
    close         NUMERIC(19,6) NOT NULL,

    -- Zero is a legitimate value: 26 of ATW's 245 recorded sessions had no
    -- trades. Anything dividing by volume (Amihud illiquidity) must guard for it.
    volume        BIGINT        NOT NULL DEFAULT 0,
    ingested_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- The partition key MUST be part of every unique constraint on a partitioned
    -- table — Postgres cannot enforce uniqueness across partitions otherwise.
    -- (ticker, session_date) satisfies that and is also the natural key.
    CONSTRAINT pk_ohlcv_bar PRIMARY KEY (ticker, session_date),

    CONSTRAINT ck_ohlcv_high_is_highest CHECK (high >= open AND high >= close AND high >= low),
    CONSTRAINT ck_ohlcv_low_is_lowest   CHECK (low  <= open AND low  <= close),
    CONSTRAINT ck_ohlcv_volume_non_negative CHECK (volume >= 0)
) PARTITION BY RANGE (session_date);

-- ── Monthly partitions ──────────────────────────────────────────────────────
-- Generated for 2024-01 .. 2027-12. In production a scheduled job would create
-- next month's partition ahead of time (pg_partman does this); here the range is
-- wide enough for the project's lifetime and the mechanism is what matters.
DO $$
DECLARE
    start_month DATE := DATE '2024-01-01';
    end_month   DATE := DATE '2028-01-01';
    m           DATE;
BEGIN
    m := start_month;
    WHILE m < end_month LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS ohlcv_bar_%s PARTITION OF ohlcv_bar '
            'FOR VALUES FROM (%L) TO (%L)',
            to_char(m, 'YYYY_MM'), m, m + INTERVAL '1 month'
        );
        m := m + INTERVAL '1 month';
    END LOOP;
END $$;

-- Catch-all so an out-of-range backfill fails loudly at query time rather than
-- silently rejecting the INSERT. Rows landing here are a signal to add partitions.
CREATE TABLE ohlcv_bar_default PARTITION OF ohlcv_bar DEFAULT;

-- ── Indexes ─────────────────────────────────────────────────────────────────
-- Created on the parent; Postgres cascades them to every partition, existing
-- and future.
--
-- BRIN rather than B-tree on session_date: rows arrive in date order, so the
-- physical layout already correlates with the value. BRIN stores min/max per
-- block range instead of one entry per row — a few KB versus a few MB, at the
-- cost of being useless if the correlation is ever destroyed (e.g. by a bulk
-- re-import in random order). Worth naming that trade-off out loud.
CREATE INDEX idx_ohlcv_session_date_brin ON ohlcv_bar USING BRIN (session_date);

-- B-tree for the dominant query: one instrument's series, newest first.
CREATE INDEX idx_ohlcv_ticker_date ON ohlcv_bar (ticker, session_date DESC);
