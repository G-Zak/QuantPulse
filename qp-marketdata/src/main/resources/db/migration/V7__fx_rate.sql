-- ============================================================================
--  V7 — foreign exchange rates
--
--  Every price on the Casablanca exchange is in MAD. FX exists so an amount in
--  another currency (a USD dividend, a EUR benchmark) can be expressed in MAD
--  with an explicit, dated, auditable rate — never an implicit one. Money
--  refuses to add two currencies precisely so that this table has to exist.
-- ============================================================================

CREATE TABLE fx_rate (
    -- VARCHAR(3), not CHAR(3). Hibernate maps a Java String to VARCHAR, and
    -- ddl-auto=validate rejects a CHAR column as a type mismatch — the same
    -- failure this schema already hit once (see PROJECT-STATUS, bug 1).
    base_currency   VARCHAR(3)    NOT NULL,
    quote_currency  VARCHAR(3)    NOT NULL,

    -- The market date the rate applies to, not when we fetched it. Rates do not
    -- exist for every calendar day (weekends, holidays, a missed run), so readers
    -- ask for "the latest rate on or before date D" rather than "the rate for D".
    rate_date       DATE          NOT NULL,

    -- Units of quote per one unit of base: USD/MAD 9.95 means 1 USD = 9.95 MAD.
    -- NUMERIC for the same reason every money column is — the rate multiplies
    -- directly into Money.amount, and a double would carry binary error in.
    rate            NUMERIC(19,6) NOT NULL,

    -- Where the rate came from ('ALPHAVANTAGE', 'FIXTURE'). A converted amount is
    -- only as trustworthy as its rate, so provenance travels with it.
    source          VARCHAR(32)   NOT NULL,
    ingested_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- One rate per pair per day. A same-day re-fetch replaces the row rather than
    -- adding a second one that readers would then have to choose between.
    -- The PK index also serves the "latest on or before" lookup: equality on the
    -- pair, then a backward range scan on rate_date — so no second index is needed.
    CONSTRAINT pk_fx_rate PRIMARY KEY (base_currency, quote_currency, rate_date),

    CONSTRAINT ck_fx_rate_positive CHECK (rate > 0),
    -- A USD/USD row is always a bug: Money.convert rejects same-currency
    -- conversion, so such a rate could only ever be read by mistake.
    CONSTRAINT ck_fx_rate_distinct_pair CHECK (base_currency <> quote_currency),
    CONSTRAINT ck_fx_rate_iso_codes CHECK (
        base_currency  ~ '^[A-Z]{3}$' AND
        quote_currency ~ '^[A-Z]{3}$'
    )
);
