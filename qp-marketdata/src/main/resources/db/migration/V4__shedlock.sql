-- ============================================================================
--  V4 — ShedLock
--
--  @Scheduled fires on every instance. Two replicas of qp-marketdata would each
--  run the ingestion cycle, doubling API spend against a hard 100/day limit —
--  the failure is not "duplicate work", it is "budget exhausted by lunchtime".
--
--  ShedLock takes a row lock here before a scheduled method runs; whoever loses
--  the race skips silently. This is a *lock*, not a scheduler: it prevents
--  concurrent execution, it does not guarantee a missed run is made up.
-- ============================================================================

CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
