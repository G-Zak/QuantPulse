-- ============================================================================
--  V6 — allow FUNDAMENTALS backfill tasks
--
--  Payout ratio needs earnings per share, which the feed only exposes indirectly
--  as a P/E on the per-instrument detail endpoint. That is one call per
--  instrument, so it drains through the same priority queue as everything else
--  rather than being fetched on demand. Coverage is partial until the queue
--  drains, and the income analysis reports "unavailable" rather than guessing.
-- ============================================================================

ALTER TABLE backfill_task DROP CONSTRAINT IF EXISTS ck_backfill_kind;
ALTER TABLE backfill_task ADD CONSTRAINT ck_backfill_kind
    CHECK (kind IN ('OHLCV', 'DIVIDEND', 'FUNDAMENTALS'));
