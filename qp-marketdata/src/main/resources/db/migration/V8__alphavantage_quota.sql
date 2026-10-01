-- ============================================================================
--  V8 — Alpha Vantage daily call budget
--
--  A second upstream with its own hard daily limit (25/day on the free tier).
--  Kept in its own table rather than widening quota_budget's key to
--  (provider, quota_date): that key sits under the row lock Drahmi takes on
--  every call, and changing it for a second provider would touch the one code
--  path this service cannot afford to break. See ADR-006.
--
--  Same reasoning as quota_budget for living in Postgres rather than Redis:
--  a lost count of calls already spent cannot be reconstructed until the
--  upstream resets.
-- ============================================================================

CREATE TABLE alphavantage_quota_budget (
    quota_date     DATE         PRIMARY KEY,
    daily_limit    INTEGER      NOT NULL,
    consumed       INTEGER      NOT NULL DEFAULT 0,

    -- Alpha Vantage sends no X-RateLimit-Remaining header. The only signal that
    -- it considers the key exhausted is a 200 response whose body is a "Note" or
    -- "Information" message instead of data. When that happens we record it and
    -- stop spending for the day — the equivalent of Drahmi's "believe the
    -- upstream when it says less remains".
    upstream_exhausted BOOLEAN  NOT NULL DEFAULT FALSE,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_av_quota_consumed_non_negative CHECK (consumed >= 0),
    CONSTRAINT ck_av_quota_limit_positive CHECK (daily_limit > 0)
);

-- Calls are audited in the existing api_call_log, with endpoints prefixed
-- 'alphavantage:', so "what spent the budget today?" has one answer per
-- provider in one table.
