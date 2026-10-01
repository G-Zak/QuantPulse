# ADR-001 — Maven reactor with imported BOMs, not an inherited Boot parent

- **Status:** Accepted
- **Date:** 2026-08-03
- **Supersedes:** FinTrack's three standalone Maven projects

## Context

FinTrack was three unrelated Maven projects, each inheriting from
`spring-boot-starter-parent`, each built by `cd`-ing into its directory. `PriceEvent` —
the message crossing the RabbitMQ boundary — existed as two independent copies with
different fields. Nothing enforced that they agreed, and nothing would have detected
divergence.

## Decision

A single Maven reactor at the repository root. It does **not** inherit from
`spring-boot-starter-parent`; instead it imports `spring-boot-dependencies` and
`testcontainers-bom` via `<scope>import</scope>` in `<dependencyManagement>`.

Shared event contracts live in `qp-common`, which has **no Spring dependency** and is
**not** repackaged by `spring-boot-maven-plugin`.

## Rationale

**Why a reactor.** Maven topologically sorts modules and resolves inter-module
dependencies from the in-memory reactor rather than `~/.m2`. One `mvn verify` builds
everything in the right order against the current source — no stale installed jars.

**Why import rather than inherit.** Maven permits exactly one `<parent>`. In any real
organisation that slot holds the company parent POM, so inheriting Boot's parent is a
technique that doesn't survive contact with a corporate build. BOM import composes:
we import Boot's and Testcontainers' together, which inheritance cannot do.

**Why `qp-common` has no Spring dependency.** A contract module crossing service
boundaries must not pin its consumers to a framework version. If `qp-common` dragged in
Spring, a service on a different Spring version — or a non-Spring consumer — could not
use the contract at all.

**Why `qp-common` is not repackaged.** `repackage` relocates classes into
`BOOT-INF/classes/`, which is invisible to a normal classpath scan. A Boot executable
jar is a runnable application, not a consumable library.

## Consequences

**Accepted costs.** We configure `maven-compiler-plugin` (including `-parameters`),
Surefire, Failsafe and `spring-boot-maven-plugin` versions ourselves, and we lose the
parent's `application.properties` resource filtering and UTF-8 defaults. All of these
are now explicit in the parent POM, which we consider a benefit.

**Ongoing obligation.** Versions not covered by an imported BOM — Resilience4j,
ShedLock, springdoc, WireMock — must be pinned by hand in `<dependencyManagement>`.
Left unpinned, Maven's *nearest-wins* mediation would decide them, which is neither
"highest version" nor stable across dependency changes.

## Alternatives rejected

- **Keep three standalone projects.** Cheapest, but leaves the duplicated-contract
  problem that motivated the rebuild.
- **Inherit `spring-boot-starter-parent` in the reactor root.** Less configuration up
  front, but burns the single `<parent>` slot and teaches a pattern that breaks in a
  corporate build.
- **Gradle.** Better multi-module ergonomics, but Maven is what most banks use.
