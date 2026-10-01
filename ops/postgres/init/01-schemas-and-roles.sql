-- Database setup. Runs once, on the first container start. To re-run:
--     docker compose down -v && docker compose up -d
--
-- One Postgres, one schema and one role per service, no cross-schema grants.
-- A service can only read its own tables, so it has to use the other
-- service's API or events to get its data.

-- Roles (dev passwords only)
CREATE ROLE qp_market    WITH LOGIN PASSWORD 'qp_market_dev';
CREATE ROLE qp_portfolio WITH LOGIN PASSWORD 'qp_portfolio_dev';
CREATE ROLE qp_alerts    WITH LOGIN PASSWORD 'qp_alerts_dev';
CREATE ROLE qp_insights  WITH LOGIN PASSWORD 'qp_insights_dev';

-- Schemas. The service role owns its schema so Flyway can create tables.
CREATE SCHEMA market    AUTHORIZATION qp_market;
CREATE SCHEMA portfolio AUTHORIZATION qp_portfolio;
CREATE SCHEMA alerts    AUTHORIZATION qp_alerts;
CREATE SCHEMA insights  AUTHORIZATION qp_insights;

-- Nobody creates tables in public
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- No role can use another service's schema
REVOKE ALL ON SCHEMA market    FROM qp_portfolio, qp_alerts,    qp_insights;
REVOKE ALL ON SCHEMA portfolio FROM qp_market,    qp_alerts,    qp_insights;
REVOKE ALL ON SCHEMA alerts    FROM qp_market,    qp_portfolio, qp_insights;
REVOKE ALL ON SCHEMA insights  FROM qp_market,    qp_portfolio, qp_alerts;

-- search_path per role. public is left out on purpose so a missing table fails
-- instead of being found somewhere else.
ALTER ROLE qp_market    SET search_path = market;
ALTER ROLE qp_portfolio SET search_path = portfolio;
ALTER ROLE qp_alerts    SET search_path = alerts;
ALTER ROLE qp_insights  SET search_path = insights;

-- Extensions. btree_gist is used by the alert cooldown constraint.
CREATE EXTENSION IF NOT EXISTS btree_gist;
