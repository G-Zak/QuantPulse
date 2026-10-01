-- One row per generated brief. Regenerations add rows; the latest is what the UI shows,
-- and the earlier ones stay as an audit trail of what the model said and why it was
-- accepted or replaced.
CREATE TABLE market_brief (
    id               BIGSERIAL    PRIMARY KEY,
    brief_date       DATE         NOT NULL,
    generated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    trigger_kind     VARCHAR(16)  NOT NULL,          -- SCHEDULED | CATCH_UP | MANUAL
    source           VARCHAR(16)  NOT NULL,          -- CLAUDE | TEMPLATE
    model            VARCHAR(64),
    headline         TEXT         NOT NULL,
    body             TEXT         NOT NULL,
    facts_used       TEXT         NOT NULL DEFAULT '[]',   -- JSON array of fact paths
    facts            TEXT         NOT NULL,                -- JSON: exactly what the model saw
    fallback_reason  TEXT,                                 -- why a template was served instead
    rejected_draft   TEXT,                                 -- the model's text, if validation refused it
    input_tokens     INTEGER,
    output_tokens    INTEGER,
    latency_ms       INTEGER,
    CONSTRAINT ck_brief_source CHECK (source IN ('CLAUDE', 'TEMPLATE'))
);
CREATE INDEX ix_market_brief_latest ON market_brief (generated_at DESC);

-- Daily cap on model calls: the quota-governor pattern applied to a paid API.
CREATE TABLE llm_budget (
    budget_date  DATE        PRIMARY KEY,
    daily_limit  INTEGER     NOT NULL,
    consumed     INTEGER     NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_llm_budget_consumed CHECK (consumed >= 0)
);

CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
