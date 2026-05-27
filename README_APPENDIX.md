## Interpreting comparison results (in-memory vs Redis sliding-window)

When you run `compare_strategies.py` you will get a JSON file with two reports (A and B).

Key signals and interpretation:

- `gatewayHeader.p95Ms` difference:
  - If Redis-backed gateway has significantly higher p95/p99 than in-memory, Redis latency contributes to tail increases.
- `rateLimiterHeader` (X-RateLimit-Latency-Ms):
  - Shows how long the limiter check took. For `in-memory` this should be near-zero (<1ms). For `redis-sliding-window` it approximates Redis RTT + Lua script execution time.
- `backendHeader` (X-Backend-Latency-Ms):
  - If backendHeader is similar across runs, differences in gatewayHeader are due to rate limiter and/or gateway overhead.
- `clientObserved` vs `gatewayHeader`:
  - ClientObserved includes network overhead and delays; use gatewayHeader to isolate gateway internal latency.

Practical expectations:
- Baseline (no backend delay): in-memory << redis in gatewayHeader and rateLimiterHeader.
- With backend delay set (delayMs): backendHeader dominates gatewayHeader; Redis overhead becomes proportionally smaller but still affects tail percentiles.

Recommendations:
- If Redis latency dominates, consider local caching or batching checks, or moving to token-bucket approximations to reduce Redis calls.
- Use Redis Cluster with colocated gateway instances to minimize RTT.

Sample command to run comparison (assumes two gateway instances are running on different ports):

```powershell
python compare_strategies.py --url-a http://localhost:8080/api/v1/hello --label-a redis --url-b http://localhost:8082/api/v1/hello --label-b inmemory --requests 200 --concurrency 20 --delay-ms 50 --output reports/compare_report.json
```

The generated JSON contains `reportA`, `reportB`, and a small `comparisonSummary` with p95 difference. Use that to create plots or include in your paper/report.
