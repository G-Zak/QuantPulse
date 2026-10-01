-- ============================================================================
--  V1 — reference data: sectors, instruments, indices
--
--  Runs as qp_market, whose search_path is `market`, so unqualified names land
--  in the right schema. See ops/postgres/init/01-schemas-and-roles.sql.
-- ============================================================================

-- ── Sectors ─────────────────────────────────────────────────────────────────
-- The upstream returns 25 French sector codes, two of which contain non-ASCII
-- characters (SANTÉ, TÉLÉC). That rules out CHAR(n) — which is sized in bytes
-- under UTF-8 — and it is why `code` is a unique business key over a surrogate
-- id rather than the primary key itself. A natural key you do not control is a
-- natural key that will eventually change under you.
CREATE TABLE sector (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    instrument_count INTEGER     NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sector_code UNIQUE (code)
);

-- ── Instruments ─────────────────────────────────────────────────────────────
CREATE TABLE instrument (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticker          VARCHAR(16)  NOT NULL,
    name            VARCHAR(255) NOT NULL,
    isin            VARCHAR(12),
    sector_code     VARCHAR(32),
    exchange        VARCHAR(64)  NOT NULL DEFAULT 'Bourse de Casablanca',
    currency        VARCHAR(3)   NOT NULL DEFAULT 'MAD',

    -- NUMERIC(19,6): exact decimal, six places to survive intermediate division.
    -- Never DOUBLE PRECISION for anything money-shaped.
    last_price      NUMERIC(19,6),
    last_price_at   TIMESTAMPTZ,
    market_cap      NUMERIC(24,2),
    dividend_yield  NUMERIC(9,4),
    pe_ratio        NUMERIC(12,4),
    week52_high     NUMERIC(19,6),
    week52_low      NUMERIC(19,6),

    -- Upstream returns beta as null in practice despite documenting a value,
    -- so it stays nullable and we compute our own in qp-portfolio.
    beta            NUMERIC(12,6),

    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_instrument_ticker UNIQUE (ticker),
    CONSTRAINT fk_instrument_sector FOREIGN KEY (sector_code)
        REFERENCES sector (code) ON DELETE SET NULL,
    CONSTRAINT ck_instrument_prices_non_negative
        CHECK (last_price IS NULL OR last_price >= 0)
);

CREATE INDEX idx_instrument_sector ON instrument (sector_code);
-- Partial index: only ~81 rows today, but the query that matters ("all tradable
-- instruments") always filters on active, so the index stays small as delistings
-- accumulate.
CREATE INDEX idx_instrument_active ON instrument (ticker) WHERE active;

-- ── Indices (MASI, MASI20) ──────────────────────────────────────────────────
-- Only two exist upstream despite the docs mentioning MADEX.
CREATE TABLE market_index (
    code            VARCHAR(16)  PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    value           NUMERIC(19,6),
    change_percent  NUMERIC(12,6),
    change_value    NUMERIC(19,6),
    as_of           TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Daily closes for each index. Needed as the benchmark series for beta/alpha.
CREATE TABLE index_history (
    code            VARCHAR(16)  NOT NULL,
    session_date    DATE         NOT NULL,
    value           NUMERIC(19,6) NOT NULL,
    change_percent  NUMERIC(12,6),
    change_value    NUMERIC(19,6),
    PRIMARY KEY (code, session_date),
    CONSTRAINT fk_index_history_code FOREIGN KEY (code)
        REFERENCES market_index (code) ON DELETE CASCADE
);
