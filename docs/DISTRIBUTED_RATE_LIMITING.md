# Distributed Rate Limiting Validation

Stage 1 proves that GateShield can run multiple stateless gateway replicas while enforcing one shared global quota through Redis.

## Architecture

```text
Client
  |
Nginx load balancer :8100
  |-- GateShield replica scaled-a
  |-- GateShield replica scaled-b
  `-- GateShield replica scaled-c
             |
          Shared Redis
             |
        Shared PostgreSQL
             |
        Mock backend
```

Redis and PostgreSQL are private Docker services. Only the load balancer is exposed for the scaled validation path.

## Invariant

For one tenant and one route with limit `N`, all active GateShield replicas combined must allow no more than `N` requests during the route's configured window when using the Redis-backed sliding-window strategy.

The rate-limit identity is created by the gateway from the authenticated tenant and matched route:

```text
ratelimit:{tenantId}:{routeId}
```

For anonymous fallback paths, the key includes client IP and request URI, but protected route validation uses tenant plus route.

## Atomicity

The Redis strategies use Spring Data Redis `RedisScript` execution. The active production strategy, `redis-sliding-window`, executes a Lua script that:

1. Removes expired sorted-set members.
2. Counts the current window.
3. Adds the request member only when the limit has not been reached.
4. Sets the key TTL.
5. Returns allowed/rejected, remaining quota, and reset time.

Redis executes each Lua script atomically, so concurrent requests from multiple replicas cannot interleave the check and consume steps.

Existing strategies:

| Strategy | Shared Across Replicas | Atomic Mechanism |
| --- | --- | --- |
| `redis-sliding-window` | Yes | Redis Lua script |
| `redis-fixed-window` | Yes | Redis Lua script |
| `redis-token-bucket` | Yes | Redis Lua script |
| `in-memory` | No | Per-process synchronized local map |

## Run The Scaled Deployment

PowerShell:

```powershell
$env:GATESHIELD_ADMIN_TOKEN="<admin-token>"
$env:SCALED_RATE_LIMIT_STRATEGY="redis-sliding-window"
docker compose --profile scaled up -d --build
docker compose --profile scaled restart gateway-lb
```

Bash:

```bash
export GATESHIELD_ADMIN_TOKEN="<admin-token>"
export SCALED_RATE_LIMIT_STRATEGY=redis-sliding-window
docker compose --profile scaled up -d --build
docker compose --profile scaled restart gateway-lb
```

Check services:

```bash
docker compose --profile scaled ps
curl http://localhost:8100/admin/health
```

## Run The Distributed Test

The script creates or updates a route, creates a temporary tenant, keeps the generated API key in memory only, and sends concurrent requests through the load balancer.

```bash
python scripts/distributed_rate_limit_test.py \
  --base-url http://localhost:8100 \
  --admin-base-url http://localhost:8100 \
  --strategy redis-sliding-window \
  --expected-limit 12 \
  --total-requests 60 \
  --concurrency 30
```

Expected Redis result:

```text
Allowed = configured limit
Rejected with 429 = total requests - configured limit
Quota overshoot = 0
Gateway instances observed includes scaled-a, scaled-b, and scaled-c
```

## Compare With In-Memory

PowerShell:

```powershell
$env:GATESHIELD_ADMIN_TOKEN="<admin-token>"
$env:SCALED_RATE_LIMIT_STRATEGY="in-memory"
docker compose --profile scaled up -d --build --force-recreate api-gateway-scaled-a api-gateway-scaled-b api-gateway-scaled-c gateway-lb
docker compose --profile scaled restart gateway-lb
python scripts/distributed_rate_limit_test.py --strategy in-memory --expected-limit 12 --total-requests 60 --concurrency 30 --allow-overshoot 100
```

In-memory mode is expected to allow up to `limit x replica count`, because each gateway process keeps its own local counter. It is useful for development or fallback behavior, but it cannot guarantee a global distributed quota.

## Result Format

```text
Strategy | Replicas | Limit | Allowed | Rejected | Overshoot | p95
```

Use the script output for measured values. Do not copy old benchmark numbers into reports; rerun the test for the current machine and configuration.

## Teacher Demo Workflow

1. Start the scaled Redis deployment.
2. Run `docker compose --profile scaled ps` and show three private gateway replicas.
3. Run the distributed test.
4. Point out all three `X-Gateway-Instance-Id` values in the output.
5. Show `Allowed` equals the configured limit.
6. Show `Quota overshoot` is zero.
7. Switch `SCALED_RATE_LIMIT_STRATEGY` to `in-memory`.
8. Force-recreate the scaled gateway replicas.
9. Run the same test with `--allow-overshoot`.
10. Explain why local memory produces per-replica quotas instead of a global quota.

## Known Limitations

- The validation script tests the HTTP path through Nginx, not Kubernetes or cloud load balancers.
- The script intentionally avoids printing API keys.
- `in-memory` mode is not a distributed quota mechanism.
- The demo uses a mock backend and local Docker networking.
