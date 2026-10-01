-- Global benchmarks (Alpha Vantage TIME_SERIES_DAILY) stored in their own currency.
-- Conversion to MAD happens at read time against fx_rate for the same date, so a
-- corrected rate fixes every comparison without rewriting price history.
CREATE TABLE benchmark_history (
    symbol        VARCHAR(16)   NOT NULL,
    session_date  DATE          NOT NULL,
    close         NUMERIC(19,6) NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    source        VARCHAR(32)   NOT NULL,
    ingested_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_benchmark_history PRIMARY KEY (symbol, session_date),
    CONSTRAINT ck_benchmark_close_positive CHECK (close > 0),
    CONSTRAINT ck_benchmark_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
);

-- The snapshot has always carried each stock's day change; it was dropped on the floor.
-- Stored now so "today's movers" is one indexed read instead of 81 history fetches.
ALTER TABLE instrument ADD COLUMN day_change_percent NUMERIC(9,4);
