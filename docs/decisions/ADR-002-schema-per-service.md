# ADR-002 — One Postgres instance, one schema and role per service

- **Status:** Accepted
- **Date:** 2026-08-03

## Context

Four services need persistence. The textbook microservices answer is a database per
service. Locally that means four or five Postgres containers, on a machine with 10 GB
free disk, for a project whose point is learning rather than production operation.

The risk we actually need to eliminate is the **shared-database anti-pattern**: service A
reads service B's tables because the `JOIN` is right there and it works. Once that
happens no schema can change independently and the system is a distributed monolith.

## Decision

One Postgres 16 instance. Per service: one schema, one login role that **owns** that
schema, and **no cross-schema grants**. Each role's `search_path` is set to its own
schema and deliberately excludes `public`.

Bootstrap lives in `ops/postgres/init/01-schemas-and-roles.sql`. Tables live in
per-service Flyway migrations.

## Rationale

The guarantee that matters is *service A cannot read service B's tables*, and in
Postgres a missing `GRANT` enforces that as absolutely as a separate process would.
Verified: `qp_portfolio` selecting from `market.*` fails with
`ERROR: permission denied for schema market`.

Excluding `public` from `search_path` means an unqualified name missing from the
service's own schema raises an error instead of silently resolving to a shared object.

## Consequences

**Accepted costs, and they are real.** A single instance is a single point of failure
with a shared connection pool and shared buffer cache, so a noisy neighbour in `market`
can degrade `portfolio`. There is no per-service tuning, backup cadence, or independent
major-version upgrade.

**Mitigating property.** Because the isolation *semantics* already match
database-per-service, splitting physically later is an operational migration — change
connection strings, run each service's migrations against its own instance — not a code
change. Enforcing isolation now, before coupling exists, is what makes that true.

**Known gap.** Each service's runtime role is also its DDL role, so there is no
least-privilege boundary between running the app and altering its schema. Production
would split this into a `*_ddl` owner running Flyway and a `*_app` role holding only
DML, granted through `ALTER DEFAULT PRIVILEGES`. Deliberately deferred; documented so
it can be answered rather than discovered.

## Alternatives rejected

- **Database per service.** Correct for production, disproportionate locally, and
  identical in the guarantee it provides.
- **One shared schema.** The anti-pattern this ADR exists to prevent.
- **Separate instances via Docker Compose profiles.** Same resource cost, more moving
  parts, no additional learning.
