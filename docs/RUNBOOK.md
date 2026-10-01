# QuantPulse — Runbook

**Every command and every piece of output below was run against this repository on
2026-08-24** — this isn't a transcription of what should happen, it's what did happen,
including the two real problems hit along the way and their actual fixes (both now folded
back into the source, so a fresh clone won't hit them — see [Troubleshooting](#troubleshooting)
for what to do if a *new* instance of either shows up).

---

## Prerequisites

- Docker Desktop running (`docker info` should succeed, not error)
- `.env` present at the repo root — copy from `.env.example` and fill in `DRAHMI_API_KEY`
  if you ever intend to run the `live` profile. **For everything below, the key is
  irrelevant** — the `dev` profile (the default) never calls it.
- Ports free on the host: `3001`, `3002`, `5434`, `5672`, `6380`, `8080–8084`, `9090`,
  `15672`. `5434`/`6380` are deliberately non-default — this machine already runs its own
  Postgres on `5432` and Redis on `6379`.

```bash
docker info >/dev/null 2>&1 && echo "Docker OK" || echo "Start Docker Desktop first"
ls -la .env 2>/dev/null || echo "cp .env.example .env, then fill in DRAHMI_API_KEY"
```

---

## Quick start — everything, from a clean slate

```bash
cd ops
docker compose --env-file ../.env up -d                                          # infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml \
               --env-file ../.env up -d --build                                  # + apps
```

The second command is idempotent with the first — running both back to back (as shown) is
the normal path and brings up all eleven containers. First build takes a few minutes (Maven
resolves dependencies, npm installs, pip installs); subsequent builds are fast because of
Docker's layer cache (see `concepts/docker.md` for why the Dockerfiles are ordered the way
they are).

**Verify it actually finished** — containers reporting `Up` is not the same as the JVM
finishing Spring Boot startup. Poll the real health endpoints:

```bash
for svc in "marketdata:8081" "portfolio:8082" "alerts:8083" "api:8080"; do
  port="${svc##*:}"
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/actuator/health")
  echo "${svc%%:*}: HTTP $code"
done
curl -s http://localhost:8084/health                 # qp-quant
curl -s -o /dev/null -w "qp-web: HTTP %{http_code}\n" http://localhost:3002
```

Expect `200` from all four Java services and `qp-web`, and `{"status":"UP","service":"qp-quant"}`
from `qp-quant`. **This is what a first boot actually looks like** — the Java services take
20–40 seconds after the container reports `Up` before they answer `200`; polling in a loop is
correct, a single immediate check is not:

```
check 1: 0/4 Java services healthy
check 4: 1/4 Java services healthy
check 9: 4/4 Java services healthy      ← this is normal, not a failure
```

---

## Service reference — verified live

| Service | URL | What a healthy response looks like |
|---|---|---|
| Dashboard | http://localhost:3002 | `HTTP 200` |
| Dashboard login | — | `zakaria` / `quantpulse` (`DEMO_USER` / `DEMO_PASSWORD` in `.env`) |
| BFF (`qp-api`) | http://localhost:8080/actuator/health | `{"status":"UP"}` |
| Market data | http://localhost:8081/actuator/health | `{"status":"UP"}` |
| Portfolio | http://localhost:8082/actuator/health | `{"status":"UP"}` |
| Alerts | http://localhost:8083/actuator/health | `{"status":"UP"}` |
| Analytics (`qp-quant`) | http://localhost:8084/health | `{"status":"UP","service":"qp-quant"}` |
| Postgres | `localhost:5434` | `docker exec qp-postgres pg_isready` |
| RabbitMQ AMQP | `localhost:5672` | — |
| RabbitMQ management UI | http://localhost:15672 | login `quantpulse` / `qp_local_dev` (or your `.env` values) |
| Redis | `localhost:6380` | `docker exec qp-redis redis-cli ping` → `PONG` |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3001 | login `admin` / `.env`'s `GRAFANA_ADMIN_PASSWORD` |

---

## Proving the system actually works, not just that it started

"The containers are up" is the weakest possible verification. This is what real confirmation
looks like — every one of these was run against the live stack:

**1. Real market data, aggregated through the BFF, costing zero API quota:**
```bash
curl -s http://localhost:8080/api/v1/market/dashboard | head -c 300
curl -s http://localhost:8080/api/v1/market/quota
# {"date":"2026-08-24","dailyLimit":100,"consumed":0,"remaining":100,"upstreamRemaining":null}
```
`consumed: 0` after a dashboard load is the entire point of the caching + fixture-replay
design — see [`ARCHITECTURE.md`](ARCHITECTURE.md) §1.

**2. Trigger an ingestion cycle and watch the event pipeline actually move data:**
```bash
curl -s -X POST http://localhost:8081/api/v1/ops/ingest
# {"priceChanges":75,"created":0,"indicesChanged":2,"marketCapsHarvested":60,"seen":81}

docker exec qp-postgres psql -U quantpulse -d quantpulse -c \
  "SELECT count(*) FROM market.outbox_event WHERE published_at IS NULL;"
#  unpublished
# -------------
#            0        ← the relay drained it; nothing stuck

curl -s -u quantpulse:qp_local_dev http://localhost:15672/api/queues | \
  python3 -c "import json,sys; [print(f\"{q['name']}: msgs={q['messages']} consumers={q['consumers']}\") for q in json.load(sys.stdin)]"
# alerts.evaluation: msgs=0 consumers=2
# portfolio.valuation: msgs=0 consumers=2
# api.price-stream.<uuid>: msgs=0 consumers=1
```
Both `qp-portfolio` and `qp-alerts` show 2 consumers (the configured
`concurrency: "2-4"`), and `qp-api`'s SSE queue shows exactly 1 per connected browser tab.
Zero messages sitting in any queue after ingestion confirms the whole chain —
outbox write → relay poll → publish → fan-out → both consumers apply their effect — ran
correctly, not just that the broker is reachable.

**3. Schema isolation, proven by an actual permission denial, not just asserted in a doc:**
```bash
docker exec qp-postgres psql -U qp_portfolio -d quantpulse -c \
  "SELECT * FROM market.instrument LIMIT 1;"
# ERROR:  permission denied for schema market
```
This is [ADR-002](decisions/ADR-002-schema-per-service.md) verified against a running
database, not a claim in a markdown file.

---

## Common operations

```bash
# Logs, one service
docker logs -f qp-marketdata

# Logs, everything
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml logs -f

# Shell into Postgres as the superuser
docker exec -it qp-postgres psql -U quantpulse -d quantpulse

# Shell into Postgres as one service's own role (to test isolation, or debug that service's data)
docker exec -it qp-postgres psql -U qp_portfolio -d quantpulse

# Rebuild + restart one service after a code change
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml \
  --env-file ../.env up -d --build qp-portfolio

# Restart without rebuilding (config-only change, e.g. an env var)
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml \
  --env-file ../.env up -d qp-portfolio

# Full teardown, keep data
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml down

# Full teardown, WIPE all data (fresh Postgres, fresh RabbitMQ, fresh Grafana dashboards)
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml down -v
```

Running everything outside Docker (JVM services from an IDE, against containerized infra
only) is also supported — see `README.md`'s "Running it" section for the Maven/`uvicorn`
commands. The two compose files are layered specifically so this hybrid mode works:
`docker-compose.yml` alone gives you infra with nothing else running.

---

## Troubleshooting

Two real problems were hit bringing this stack up from a stale local state. Both are now
fixed at the source (see the diffs below), so a fresh `git clone` + first boot won't
reproduce either — but the *symptoms* are worth knowing, because the underlying failure mode
in each case can resurface from unrelated causes (a corrupted volume, a new service that
forgets the same wiring).

### "Too short cookie string" — RabbitMQ crash-loops on boot

**Symptom:**
```
docker logs qp-rabbitmq
...crasher: ... {"Too short cookie string", [{auth,init_no_setcookie,0,...
Kernel pid terminated (application_controller)
```
`docker ps` shows `qp-rabbitmq` cycling `Restarting (1) N seconds ago`.

**Root cause:** RabbitMQ persists its Erlang distribution cookie
(`/var/lib/rabbitmq/.erlang.cookie`) inside the named volume `qp-rabbitdata`, generating it
fresh only on a truly empty volume. If that file gets truncated or corrupted — an unclean
container kill mid-write is the common cause — Erlang refuses to boot rather than silently
regenerate it, because a cookie mismatch across a real cluster is a security problem, not a
convenience one.

**Fix.** The volume holds no durable business data (message state only — everything that
matters lives in Postgres), so recreating it is safe and correct:
```bash
cd ops
docker compose stop rabbitmq
docker compose rm -f rabbitmq
docker volume rm quantpulse_qp-rabbitdata
docker compose --env-file ../.env up -d rabbitmq
```
Wait for `docker inspect --format='{{.State.Health.Status}}' qp-rabbitmq` to report
`healthy` (10–25 seconds) before bringing up anything that depends on it.

### `qp-portfolio` / `qp-alerts` report `{"status":"DOWN", ... "redis":{"status":"DOWN"}}`

**Symptom:** `/actuator/health` on ports `8082`/`8083` returns `503`, body shows every
component `UP` except `redis`, with `Unable to connect to Redis` /
`Connection refused: localhost/127.0.0.1:6379` in the logs. `qp-marketdata` and `qp-api` are
unaffected.

**Root cause — a real gap, found and fixed during this exact run.** All three Java services
that depend on `spring-boot-starter-data-redis` get Spring Boot's Redis actuator health
indicator autoconfigured, which means each one needs a *reachable* Redis to report healthy —
whether or not the service's own code actually uses it for caching (only `qp-marketdata`
does). `docker-compose.apps.yml` wired `REDIS_HOST`/`REDIS_PORT` for `qp-marketdata` only;
`qp-portfolio` and `qp-alerts` had no such env vars *and* no `spring.data.redis` block in
their own `application.yml`, so each fell back to Spring's hardcoded default of
`localhost:6379` — a host with nothing listening inside their own container.

**Fix, now already in the repo** — both files needed the same pairing:

`ops/docker-compose.apps.yml`, added to both `qp-portfolio` and `qp-alerts`:
```yaml
environment:
  REDIS_HOST: redis
  REDIS_PORT: 6379
depends_on:
  redis: { condition: service_healthy }
```

`qp-portfolio/src/main/resources/application.yml` and
`qp-alerts/src/main/resources/application.yml`, added under `spring:`:
```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6380}
      timeout: 2s
```
(the compose env var alone does nothing without the property mapping the app actually reads
it into — that's the specific lesson if this class of bug shows up in a *new* service:
adding the env var to the compose file and adding the property placeholder to
`application.yml` are two separate steps, and it's easy to do only one and assume it's done.)

**If you hit the `503` again after this fix is in place**, it means the container is running
stale code — rebuild rather than restart:
```bash
cd ops && docker compose -f docker-compose.yml -f docker-compose.apps.yml \
  --env-file ../.env up -d --build qp-portfolio qp-alerts
```

### Generic "a container won't come up"

```bash
docker logs <container> --tail 50           # the actual error is almost always here
docker inspect --format='{{.State.Health.Status}}' <container>
docker ps -a --filter name=qp-              # Restarting / Exited / unhealthy at a glance
```
