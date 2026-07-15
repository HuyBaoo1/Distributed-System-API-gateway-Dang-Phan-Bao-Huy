# GateShield

GateShield is a self-hosted API gateway and distributed rate limiting platform for microservice environments. It provides a practical MVP for protecting backend services with API key authentication, tenant-aware routing, Redis-backed rate limits, structured request logs, PostgreSQL persistence, and Docker-based local deployment.

The project started as a distributed API gateway and rate limiter lab, then evolved into a deployable backend platform prototype. The current goal is simple: run GateShield locally, create tenants and routes through an admin API, call backend services through the gateway, and observe authentication and rate limiting behavior end to end.

## Core Capabilities

- API key authentication using `X-API-Key`
- Tenant/client management
- Dynamic route management
- Per-tenant and per-route rate limiting
- Redis-backed distributed limiter strategies
- PostgreSQL persistence for tenants, routes, and request logs
- Protected internal admin API
- Structured request logging with latency fields
- Docker Compose deployment with gateway, Redis, PostgreSQL, and mock backend
- Optional scaled gateway profile behind Nginx
- Smoke tests and benchmark scripts

## Architecture

```text
Client
  |
  |  X-API-Key
  v
GateShield Gateway
  |
  |-- Admin API: /admin/**
  |-- API key validation
  |-- Route matching
  |-- Redis rate limiting
  |-- Request logging
  |
  +--> PostgreSQL
  |      - tenants
  |      - routes
  |      - request logs
  |
  +--> Redis
  |      - distributed rate limit state
  |
  v
Mock Backend Service
```

Rate limit keys are scoped by tenant and route:

```text
ratelimit:{tenantId}:{routeId}
```

## Repository Structure

```text
.
├── api-gateway/          # Spring Boot GateShield gateway
├── mock-backend/         # Spring Boot mock backend service
├── scripts/              # Smoke tests and local helpers
├── docs/                 # Supporting documentation
├── nginx/                # Nginx config for scaled profile
├── docker-compose.yml    # Main local deployment file
├── .env.example          # Example local configuration
└── README.md
```

## Requirements

- Docker and Docker Compose
- Java 25+ only if running Maven tests outside Docker
- Python 3.10+ only if running benchmark scripts

No paid external APIs are required.

## Quick Start

Start the default local stack:

```bash
docker compose up --build
```

Default services:

| Service | URL / Port |
| --- | --- |
| GateShield gateway | `http://localhost:8080` |
| GateShield Console | `http://localhost:3000` |
| Mock backend | `http://localhost:8081` |
| PostgreSQL | private Docker network |
| Redis | private Docker network |

The default Compose configuration uses local development values. For local overrides, copy:

```bash
cp .env.example .env
```

Do not commit `.env` or real secrets.

## Environment Variables

Important variables:

| Variable | Purpose |
| --- | --- |
| `GATESHIELD_ADMIN_TOKEN` | Token required for protected admin endpoints |
| `POSTGRES_DB` | PostgreSQL database name |
| `POSTGRES_USER` | PostgreSQL username |
| `POSTGRES_PASSWORD` | PostgreSQL password |
| `REDIS_HOST` | Redis hostname |
| `REDIS_PORT` | Redis port |
| `RATE_LIMIT_STRATEGY` | Rate limiter strategy |

Supported rate limiter strategies:

- `redis-sliding-window`
- `redis-fixed-window`
- `redis-token-bucket`
- `in-memory`

## Admin API

Health check:

```bash
curl http://localhost:8080/admin/health
```

Protected admin endpoints require:

```text
X-Admin-Token: <admin-token>
```

or:

```text
Authorization: Bearer <admin-token>
```

Available endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/admin/health` | Admin health check |
| `GET` | `/admin/routes` | List routes |
| `POST` | `/admin/routes` | Create route |
| `PUT` | `/admin/routes/{routeId}` | Update route |
| `GET` | `/admin/tenants` | List tenants |
| `POST` | `/admin/tenants` | Create tenant |
| `PUT` | `/admin/tenants/{tenantId}` | Update tenant |
| `GET` | `/admin/usage/summary` | Usage summary |

## Create A Tenant

```bash
curl -s -X POST http://localhost:8080/admin/tenants \
  -H "X-Admin-Token: change-me" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tenant-a",
    "name": "Tenant A",
    "planName": "free",
    "enabled": true
  }'
```

If `apiKey` is not provided, GateShield generates one and returns it only in the create response. Save that key locally; it is not shown again by list endpoints.

## Create A Route

```bash
curl -s -X POST http://localhost:8080/admin/routes \
  -H "X-Admin-Token: change-me" \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "mock-api",
    "pathPattern": "/api/v1/**",
    "targetUrl": "http://mock-backend:8081",
    "allowedMethods": ["GET"],
    "enabled": true,
    "rateLimitRequests": 5,
    "rateLimitWindowSeconds": 60
  }'
```

This route forwards matching requests to the mock backend service.

## Call A Protected API

```bash
curl -i http://localhost:8080/api/v1/hello \
  -H "X-API-Key: <tenant-api-key>"
```

Expected behavior:

- Missing API key: `401 Unauthorized`
- Invalid API key: `401 Unauthorized`
- Valid API key and matching route: proxied backend response
- Requests over route limit: `429 Too Many Requests`

## Response Headers

GateShield adds rate limit and latency metadata to gateway responses:

| Header | Meaning |
| --- | --- |
| `X-RateLimit-Limit` | Configured request limit |
| `X-RateLimit-Remaining` | Remaining requests in current window |
| `X-RateLimit-Reset` | Seconds until reset |
| `Retry-After` | Retry delay for rejected requests |
| `X-Gateway-Latency-Ms` | Gateway processing latency |
| `X-Backend-Latency-Ms` | Backend call latency |
| `X-RateLimit-Latency-Ms` | Rate limiter decision latency |

## Expected Project Result

After running the project, a user should be able to:

1. Start GateShield with Docker Compose.
2. Confirm gateway and admin health endpoints are available.
3. Create a tenant through the admin API.
4. Create a route to the mock backend.
5. Call the backend through GateShield using `X-API-Key`.
6. See unauthenticated requests blocked with `401`.
7. See repeated requests eventually blocked with `429`.
8. Inspect request logs and usage summary through the admin API.

This demonstrates a complete local MVP of an API gateway with authentication, dynamic routing, distributed rate limiting, and persistence.

## Smoke Test

PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke_test.ps1
```

Bash:

```bash
bash scripts/smoke_test.sh
```

The smoke test verifies:

- Gateway health
- Admin health
- Tenant creation
- Route creation
- Protected proxy request
- Unauthorized request rejection
- Rate limit rejection

## GateShield Console

The repository includes a React admin console for operating GateShield.

Run with Docker:

```bash
docker compose up --build
```

Open:

```text
http://localhost:3000
```

For frontend development:

```bash
cd frontend
npm install
npm run dev
```

See `docs/FRONTEND.md`.

## Tests

Run gateway tests:

```bash
cd api-gateway
./mvnw test
```

On Windows, run both Spring Boot modules:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_maven_tests.ps1
```

Some Redis tests use Testcontainers and require Docker.

## Optional Compose Profiles

GateShield uses one Compose file: `docker-compose.yml`.

Default deployment:

```bash
docker compose up --build
```

Scaled deployment with three gateway replicas behind Nginx:

```bash
docker compose --profile scaled up --build
```

Experiment services for comparing rate limiter strategies:

```bash
docker compose --profile experiments up --build
```

Redis Commander:

```bash
docker compose --profile tools up --build
```

## Benchmarks

Benchmark scripts are available for local experiments:

- `gateway_latency_benchmark.py`
- `run_latency_experiments.py`
- `fault_tolerance_experiment.py`
- `plot_latency_report.py`

Benchmark requests to protected routes should set:

```bash
export BENCHMARK_API_KEY="<tenant-api-key>"
```

See `docs/BENCHMARKS.md` for details.

Distributed rate-limit validation:

```bash
docker compose --profile scaled up --build
python scripts/distributed_rate_limit_test.py
```

See `docs/DISTRIBUTED_RATE_LIMITING.md` for the Stage 1 proof workflow.

## Known Limitations

- Route path rewriting is not implemented; the original request path is forwarded.
- Tenant plan names are stored, but full plan-based quota inheritance is not implemented.
- API keys use SHA-256 hashing for MVP simplicity.
- Request logs are persisted synchronously.
- Admin API authentication is token-based, not full RBAC.

## Production Roadmap

- Route rewrite rules
- Upstream health policies
- Admin RBAC and audit logs
- Tenant plan quota templates
- API key rotation and scoped keys
- Async request logging pipeline
- Prometheus/Grafana dashboards
- Kubernetes deployment manifests

## Security Notes

- Do not commit `.env`.
- Do not hardcode real API keys.
- Replace `GATESHIELD_ADMIN_TOKEN=change-me` before using GateShield outside local development.
- Store generated tenant API keys securely.
