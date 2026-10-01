-- ============================================================================
--  V1 — alert rules, firing history, notification outbox, idempotency
-- ============================================================================

CREATE TABLE alert_rule (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner          VARCHAR(128)  NOT NULL,
    ticker         VARCHAR(16)   NOT NULL,
    type           VARCHAR(32)   NOT NULL,

    -- Meaning depends on type: an absolute price for PRICE_ABOVE/BELOW,
    -- a percentage for PERCENT_MOVE and DRAWDOWN.
    threshold      NUMERIC(19,6) NOT NULL,

    enabled        BOOLEAN       NOT NULL DEFAULT TRUE,

    -- ── Hysteresis ──────────────────────────────────────────────────────────
    -- A price oscillating around a threshold would fire on every tick that
    -- crosses it — dozens of notifications for one economic event. The rule only
    -- re-arms once the price retreats past the threshold by this margin, so
    -- noise around the boundary produces exactly one alert.
    hysteresis_pct NUMERIC(9,4)  NOT NULL DEFAULT 0.5,

    -- ── Cooldown ────────────────────────────────────────────────────────────
    -- A floor on time between firings, independent of price. Hysteresis handles
    -- oscillation; cooldown handles a genuine sustained move that would
    -- otherwise re-trigger as it keeps running.
    cooldown_seconds INTEGER     NOT NULL DEFAULT 3600,

    -- ── Armed state ─────────────────────────────────────────────────────────
    -- FALSE between firing and re-arming. This is what makes evaluation
    -- edge-triggered (fire on crossing) rather than level-triggered (fire while
    -- beyond), which is almost always what a user actually wants.
    armed          BOOLEAN       NOT NULL DEFAULT TRUE,

    last_fired_at  TIMESTAMPTZ,
    last_price     NUMERIC(19,6),
    fire_count     INTEGER       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_alert_type CHECK (type IN
        ('PRICE_ABOVE', 'PRICE_BELOW', 'PERCENT_MOVE_UP', 'PERCENT_MOVE_DOWN', 'DRAWDOWN')),
    CONSTRAINT ck_alert_cooldown CHECK (cooldown_seconds >= 0),
    CONSTRAINT ck_alert_hysteresis CHECK (hysteresis_pct >= 0)
);

-- Evaluation loads every enabled rule for one ticker on each price tick, so this
-- partial index is exactly the hot path.
CREATE INDEX idx_alert_rule_ticker ON alert_rule (ticker) WHERE enabled;
CREATE INDEX idx_alert_rule_owner  ON alert_rule (owner);

-- ── Firing history ──────────────────────────────────────────────────────────
CREATE TABLE alert_firing (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id         UUID          NOT NULL,
    ticker          VARCHAR(16)   NOT NULL,
    triggered_price NUMERIC(19,6) NOT NULL,
    threshold       NUMERIC(19,6) NOT NULL,

    -- Human-readable explanation, captured at firing time. An alert whose reason
    -- has to be reverse-engineered later is an alert people learn to ignore.
    reason          TEXT          NOT NULL,
    fired_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_firing_rule FOREIGN KEY (rule_id)
        REFERENCES alert_rule (id) ON DELETE CASCADE
);

CREATE INDEX idx_firing_rule ON alert_firing (rule_id, fired_at DESC);

-- ── Notification outbox ─────────────────────────────────────────────────────
-- Same reasoning as the market-data outbox: the decision to notify commits with
-- the firing record, and delivery happens separately. Otherwise a crash between
-- "alert fired" and "email sent" either loses the notification or sends one for
-- a firing that was rolled back.
CREATE TABLE notification_outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    firing_id    BIGINT        NOT NULL,
    channel      VARCHAR(32)   NOT NULL DEFAULT 'LOG',
    recipient    VARCHAR(255)  NOT NULL,
    payload      JSONB         NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    attempts     INTEGER       NOT NULL DEFAULT 0,
    last_error   TEXT,

    CONSTRAINT fk_notification_firing FOREIGN KEY (firing_id)
        REFERENCES alert_firing (id) ON DELETE CASCADE
);

CREATE INDEX idx_notification_pending ON notification_outbox (created_at)
    WHERE delivered_at IS NULL;

-- Same idempotency mechanism as qp-portfolio: at-least-once delivery means the
-- consumer must recognise a repeat, and a PK violation is the cheapest way to.
CREATE TABLE processed_event (
    event_id     UUID         PRIMARY KEY,
    consumer     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
