# Distributed System Properties Assessment

This document maps the project to core distributed-system properties and lists what is implemented, what was added, and what remains out of scope for a local lab system.

## 1. Fault Tolerance

Implemented before this update:

- Redis-backed rate limiters support `fail-closed`, `fail-open`, and `local-fallback` policies.
- Redis Lua scripts keep each quota update atomic for fixed-window, sliding-window, and token-bucket strategies.
- Docker Compose restarts gateway and backend containers with `restart: unless-stopped`.

Added in this update:

- Backend proxy timeout configuration:
  - `BACKEND_CONNECT_TIMEOUT_MS`
  - `BACKEND_READ_TIMEOUT_MS`
- Bounded retry for idempotent backend methods: `GET`, `HEAD`, and `OPTIONS`.
- Local backend circuit breaker:
  - Opens after `BACKEND_CIRCUIT_BREAKER_FAILURE_THRESHOLD` consecutive backend failures.
  - Returns `503` while open instead of forwarding traffic to an unhealthy backend.
  - Allows traffic again after `BACKEND_CIRCUIT_BREAKER_RESET_TIMEOUT_MS`.
- Backend resilience headers:
  - `X-Backend-Attempts`
  - `X-Circuit-Breaker-State`

How to verify:

```powershell
curl.exe -i "http://localhost:8080/api/v1/hello?delayMs=100"
curl.exe "http://localhost:8080/internal/system/properties"
python fault_tolerance_experiment.py --stop-redis
```

Remaining limitation:

- Redis is still a single node in the local compose file.
- Circuit breaker state is local to each gateway instance.
- Backend is still single-instance in the main compose file.

## 2. Scalability

Implemented before this update:

- Gateway application state is not stored in local HTTP sessions.
- Redis strategies share quota state across gateway instances.
- The in-memory strategy remains available as a non-distributed baseline.

Added in this update:

- `docker-compose.scaled.yml` runs two gateway instances behind an Nginx load balancer.
- `nginx/gateway-scaled.conf` forwards traffic to both gateway replicas.
- Load-balanced endpoint: `http://localhost:8100/api/v1/hello`.

How to verify:

```powershell
docker compose -f docker-compose.yml -f docker-compose.scaled.yml up -d --build gateway-lb
curl.exe -i "http://localhost:8100/api/v1/hello?delayMs=50"
python gateway_latency_benchmark.py --url "http://localhost:8100/api/v1/hello" --requests 200 --concurrency 20 --client-id scaled-client --output reports/scaled/scaled-gateway.json
```

Remaining limitation:

- This is manual horizontal scaling, not autoscaling.
- Nginx targets are static.
- Redis can still become the shared bottleneck.

## 3. Reliability

Implemented before this update:

- Unit tests cover rate limiter behavior, Redis failure policy, latency metrics, and client identity.
- Testcontainers tests cover Redis-backed limiters when Docker is available to Java.
- Experiment scripts generate manifest, CSV, and plots from real measurements.

Added in this update:

- Unit tests for backend circuit breaker behavior.
- Unit tests for retry and circuit-open paths in `ApiGatewayController`.
- Gateway returns controlled backend failure responses:
  - `502` for generic backend client failure.
  - `504` for backend connectivity/timeout failure.
  - `503` when circuit breaker is open.

How to verify:

```powershell
.\scripts\run_maven_tests.ps1
docker compose config --quiet
docker compose -f docker-compose.yml -f docker-compose.scaled.yml config --quiet
```

Remaining limitation:

- No automated backend chaos test yet.
- No SLO threshold that fails an experiment automatically.

## 4. Observability

Implemented before this update:

- Gateway latency headers:
  - `X-RateLimit-Latency-Ms`
  - `X-Backend-Latency-Ms`
  - `X-Gateway-Latency-Ms`
- Internal latency snapshot:
  - `/internal/latency/report`
- Experiment artifacts:
  - `manifest.json`
  - `latency_comparison.csv`
  - plot PNG files

Added in this update:

- Spring Actuator health/info endpoints:
  - `/actuator/health/liveness`
  - `/actuator/health/readiness`
  - `/actuator/info`
- Runtime configuration endpoint:
  - `/internal/system/properties`
- Docker healthchecks for backend and gateway containers.

Remaining limitation:

- No Prometheus/Grafana dashboard yet.
- No distributed tracing such as OpenTelemetry yet.
- Internal endpoints are not authenticated and should be used only in a local lab environment.

## 5. Consistency

Implemented:

- Redis-backed strategies use atomic Lua scripts for quota updates.
- Multiple gateway instances can share one Redis quota state.
- Benchmark scripts isolate clients with `X-Forwarded-For`.

Remaining limitation:

- `in-memory` is intentionally not globally consistent across gateway instances.
- Redis single-node setup does not address replication lag or split-brain behavior.

## 6. Availability

Implemented:

- `fail-open` maximizes availability when Redis is unavailable.
- `local-fallback` preserves partial availability with local quota control.
- Containers restart automatically under Docker Compose.

Added in this update:

- Gateway avoids long backend waits via timeout.
- Circuit breaker prevents repeated calls to a failing backend.
- Healthchecks make container state observable to Docker Compose.

Remaining limitation:

- Main compose still has single backend and single Redis.
- Availability is configurable, not absolute: `fail-closed` intentionally trades availability for strict quota protection.

## 7. Security And Configuration Hygiene

Implemented:

- `.env` is gitignored.
- `.env.example` keeps Redis/OpenAI tokens as placeholders.
- Docker images run with non-root users.

Remaining limitation:

- No TLS in local compose.
- No auth for internal endpoints.
- No external secret manager.

## Technical Conclusion

The system is now suitable for a local academic/lab evaluation of fault tolerance, scalability, reliability, observability, consistency, and availability tradeoffs.

It should not be described as production-ready. Production readiness would require Redis high availability, backend replicas, authenticated internal endpoints, TLS, centralized metrics, tracing, and automated scaling/failover tests.
