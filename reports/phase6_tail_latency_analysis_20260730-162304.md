# GateShield Phase 6 Tail-Latency Analysis Report

## Current Phase

Gate 6B ? Scientific analysis report.

## Evidence Set

- Strategy matrix source: `reports\manual_strategy_matrix_20260730-153953\strategy-matrix\manifest.json` and `reports\manual_strategy_matrix_20260730-153953\strategy-matrix/*/*/trial-*.json`.
- Burst source: `reports\phase4_tail_latency_20260730-145813\burst/burst-*.json`.
- Fault source: `reports\phase4_tail_latency_20260730-145813\fault-tolerance\report.json` and raw files referenced by that report.
- Excluded: unauthenticated strategy matrices under `reports/phase4_tail_latency_20260730-135629/` and `reports/phase4_tail_latency_20260730-151518/`.
- Excluded: quota-dominated strategy matrix `reports/phase4_tail_latency_20260730-144046/` except as method note.
- Excluded: incomplete burst run `reports/phase4_tail_latency_20260730-145156/`.

## Repository Basis

- `gateway_latency_benchmark.py`: emits raw records, `clientLatencyMs`, gateway/backend/rate-limiter headers, status counts, and client errors.
- `run_latency_experiments.py`: defines strategy matrix ports, scenarios, trials, warm-up requests, and manifest structure.
- `burst_behavior_experiment.py`: defines burst traffic and writes per-strategy JSON.
- `fault_tolerance_experiment.py`: defines Redis healthy/down/recovered phases and raw benchmark output paths.
- `api-gateway/src/main/java/com/example/apigateway/filter/RateLimitingFilter.java`: emits gateway/rate-limit headers and rejects 429 before backend forwarding.
- `api-gateway/src/main/java/com/example/apigateway/controller/ApiGatewayController.java`: emits backend latency headers only when backend forwarding happens.

## Executive Summary

- Strategy matrix classification used here: PARTIALLY_VALID. Raw source: `reports\manual_strategy_matrix_20260730-153953\strategy-matrix\manifest.json`.
- Strategy matrix trials: 36/36 parseable; manifest comparisons: 12.
- Burst classification: VALID. Files: 4 strategy JSON files.
- Fault classification: VALID. Phases: 3; raw result count: 27.
- Lowest successful-path baseline client p99 in partial strategy evidence: `redis-fixed-window` at `920.96 ms` from raw HTTP 200 records.
- Highest successful-path client p99 in partial strategy evidence: `redis-sliding-window/baseline` at `9409.65 ms`.

Important warning: strategy matrix conclusions are partial because some strategy/scenario cells contain many client errors or 5xx responses. Percentiles below are separated by population and must not be mixed casually.

## Strategy Matrix Status Distribution

Source: each row aggregates three trial files under `reports/manual_strategy_matrix_20260730-153953/strategy-matrix/<strategy>/<scenario>/trial-*.json`.

| Strategy | Scenario | Total | HTTP 200 | HTTP 401 | HTTP 429 | HTTP 5xx | Client errors | Timeouts |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| redis-sliding-window | baseline | 600 | 592 | 0 | 7 | 0 | 1 | 1 |
| redis-sliding-window | delay-100 | 600 | 208 | 0 | 0 | 292 | 100 | 100 |
| redis-sliding-window | overload | 1500 | 751 | 0 | 2 | 48 | 699 | 699 |
| redis-fixed-window | baseline | 600 | 600 | 0 | 0 | 0 | 0 | 0 |
| redis-fixed-window | delay-100 | 600 | 600 | 0 | 0 | 0 | 0 | 0 |
| redis-fixed-window | overload | 1500 | 1500 | 0 | 0 | 0 | 0 | 0 |
| redis-token-bucket | baseline | 600 | 600 | 0 | 0 | 0 | 0 | 0 |
| redis-token-bucket | delay-100 | 600 | 600 | 0 | 0 | 0 | 0 | 0 |
| redis-token-bucket | overload | 1500 | 1500 | 0 | 0 | 0 | 0 | 0 |
| in-memory | baseline | 600 | 0 | 0 | 0 | 0 | 600 | 0 |
| in-memory | delay-100 | 600 | 0 | 0 | 0 | 0 | 600 | 0 |
| in-memory | overload | 1500 | 0 | 0 | 0 | 0 | 1500 | 0 |

## Successful-Path Tail Latency

Population: HTTP 200 responses only. Client errors, 429, and 5xx are excluded. Source: same strategy raw trial files as above.

| Strategy | Scenario | N | Client p50 | Client p95 | Client p99 | Gateway p95 | Gateway p99 | Backend p95 | Backend p99 | RateLimiter p95 | RateLimiter p99 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| redis-sliding-window | baseline | 592 | 175.99 | 5853.12 | 9409.65 | 1122.94 | 9117.60 | 775.73 | 3368.09 | 130.51 | 1263.88 |
| redis-sliding-window | delay-100 | 208 | 150.34 | 213.70 | 4128.87 | 176.53 | 233.54 | 131.17 | 170.88 | 29.19 | 33.97 |
| redis-sliding-window | overload | 751 | 285.42 | 2266.64 | 2405.69 | 241.36 | 348.32 | 190.41 | 293.93 | 43.83 | 62.16 |
| redis-fixed-window | baseline | 600 | 121.59 | 280.91 | 920.96 | 184.31 | 261.19 | 64.30 | 113.27 | 100.84 | 185.19 |
| redis-fixed-window | delay-100 | 600 | 230.67 | 525.82 | 621.89 | 287.44 | 381.26 | 219.75 | 289.45 | 68.66 | 132.89 |
| redis-fixed-window | overload | 1500 | 279.30 | 542.49 | 849.89 | 278.99 | 327.43 | 179.27 | 201.68 | 121.42 | 179.92 |
| redis-token-bucket | baseline | 600 | 120.41 | 410.59 | 3657.97 | 289.71 | 3282.21 | 68.74 | 1328.70 | 143.73 | 1881.80 |
| redis-token-bucket | delay-100 | 600 | 169.24 | 278.65 | 324.18 | 204.10 | 264.73 | 129.72 | 146.65 | 73.19 | 117.53 |
| redis-token-bucket | overload | 1500 | 324.90 | 611.35 | 705.85 | 371.94 | 474.57 | 168.46 | 221.10 | 160.74 | 251.18 |
| in-memory | baseline | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| in-memory | delay-100 | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| in-memory | overload | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |

## Completed-HTTP Tail Latency

Population: all completed HTTP responses, including 200/429/5xx, excluding client errors. Warning: this population mixes success and failure statuses.

| Strategy | Scenario | N | Client p50 | Client p95 | Client p99 | Gateway p95 | Gateway p99 | Backend p95 | Backend p99 | RateLimiter p95 | RateLimiter p99 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| redis-sliding-window | baseline | 599 | 177.57 | 8301.33 | 9491.96 | 4207.71 | 9334.57 | 775.73 | 3368.09 | 153.56 | 9196.76 |
| redis-sliding-window | delay-100 | 500 | 141.90 | 1193.46 | 4076.01 | 141.45 | 217.67 | 123.36 | 168.47 | 46.88 | 64.28 |
| redis-sliding-window | overload | 801 | 293.55 | 8074.52 | 9143.97 | 427.32 | 2963.77 | 188.81 | 293.93 | 164.61 | 2274.14 |
| redis-fixed-window | baseline | 600 | 121.59 | 280.91 | 920.96 | 184.31 | 261.19 | 64.30 | 113.27 | 100.84 | 185.19 |
| redis-fixed-window | delay-100 | 600 | 230.67 | 525.82 | 621.89 | 287.44 | 381.26 | 219.75 | 289.45 | 68.66 | 132.89 |
| redis-fixed-window | overload | 1500 | 279.30 | 542.49 | 849.89 | 278.99 | 327.43 | 179.27 | 201.68 | 121.42 | 179.92 |
| redis-token-bucket | baseline | 600 | 120.41 | 410.59 | 3657.97 | 289.71 | 3282.21 | 68.74 | 1328.70 | 143.73 | 1881.80 |
| redis-token-bucket | delay-100 | 600 | 169.24 | 278.65 | 324.18 | 204.10 | 264.73 | 129.72 | 146.65 | 73.19 | 117.53 |
| redis-token-bucket | overload | 1500 | 324.90 | 611.35 | 705.85 | 371.94 | 474.57 | 168.46 | 221.10 | 160.74 | 251.18 |
| in-memory | baseline | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| in-memory | delay-100 | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| in-memory | overload | 0 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |

## Client Error Latency

Population: client errors/timeouts only. These records have `clientLatencyMs` but no gateway/backend/rate-limiter decomposition.

| Strategy | Scenario | Errors | Client p50 | Client p95 | Client p99 | Client max |
|---|---|---:|---:|---:|---:|---:|
| redis-sliding-window | baseline | 1 | 10256.45 | 10256.45 | 10256.45 | 10256.45 |
| redis-sliding-window | delay-100 | 100 | 10036.22 | 10055.92 | 10062.81 | 10064.70 |
| redis-sliding-window | overload | 699 | 10031.32 | 10059.33 | 10079.67 | 10122.45 |
| redis-fixed-window | baseline | 0 | N/A | N/A | N/A | N/A |
| redis-fixed-window | delay-100 | 0 | N/A | N/A | N/A | N/A |
| redis-fixed-window | overload | 0 | N/A | N/A | N/A | N/A |
| redis-token-bucket | baseline | 0 | N/A | N/A | N/A | N/A |
| redis-token-bucket | delay-100 | 0 | N/A | N/A | N/A | N/A |
| redis-token-bucket | overload | 0 | N/A | N/A | N/A | N/A |
| in-memory | baseline | 600 | 52.65 | 100.15 | 126.61 | 153.34 |
| in-memory | delay-100 | 600 | 52.88 | 83.45 | 99.85 | 179.73 |
| in-memory | overload | 1500 | 122.62 | 236.39 | 274.53 | 322.65 |

## Burst Results

Population: all burst records per strategy. Source: `reports/phase4_tail_latency_20260730-145813/burst/burst-*.json`.

| Strategy | Count | Accepted | 429 | Rejection Rate | Client p50 | Client p95 | Client p99 |
|---|---:|---:|---:|---:|---:|---:|---:|
| in-memory | 120 | 12 | 108 | 0.90 | 176.25 | 328.99 | 339.23 |
| redis-fixed-window | 120 | 25 | 95 | 0.79 | 549.25 | 860.39 | 879.16 |
| redis-sliding-window | 120 | 11 | 109 | 0.91 | 92.22 | 257.20 | 266.26 |
| redis-token-bucket | 120 | 12 | 108 | 0.90 | 1254.06 | 1505.14 | 1511.57 |

## Fault-Tolerance Results

Population: each fault row is the summary emitted by `fault_tolerance_experiment.py`; raw report paths are embedded in `reports/phase4_tail_latency_20260730-145813/fault-tolerance/report.json`.

| Phase | Strategy | Policy | Responses | 429 | Errors | Client p95 | Client p99 | Gateway p99 | RateLimiter p99 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| redis-healthy | redis-fixed-window | fail-closed | 100 | 88 | 0 | 256.86 | 296.72 | 205.17 | 59.32 |
| redis-healthy | redis-fixed-window | fail-open | 100 | 80 | 20 | 5034.69 | 5038.20 | 659.99 | 525.82 |
| redis-healthy | redis-fixed-window | local-fallback | 100 | 80 | 20 | 5059.67 | 5073.92 | 1674.07 | 1578.39 |
| redis-healthy | redis-token-bucket | fail-closed | 100 | 100 | 0 | 378.24 | 415.65 | 163.23 | 81.70 |
| redis-healthy | redis-token-bucket | fail-open | 100 | 0 | 20 | 5042.39 | 5051.19 | 1201.05 | 128.42 |
| redis-healthy | redis-token-bucket | local-fallback | 100 | 80 | 20 | 5023.79 | 5036.86 | 157.82 | 137.69 |
| redis-healthy | redis-sliding-window | fail-closed | 100 | 89 | 0 | 919.65 | 1027.31 | 830.97 | 58.81 |
| redis-healthy | redis-sliding-window | fail-open | 100 | 100 | 0 | 4999.42 | 5018.87 | 4389.62 | 2880.31 |
| redis-healthy | redis-sliding-window | local-fallback | 100 | 80 | 20 | 5043.09 | 5053.66 | 3902.76 | 3662.90 |
| redis-down | redis-fixed-window | fail-closed | 100 | 100 | 0 | 2613.12 | 2654.16 | 2405.45 | 2163.17 |
| redis-down | redis-fixed-window | fail-open | 100 | 0 | 0 | 3066.99 | 3093.44 | 2932.25 | 2048.44 |
| redis-down | redis-fixed-window | local-fallback | 100 | 88 | 0 | 2951.48 | 2962.07 | 2918.22 | 2127.68 |
| redis-down | redis-token-bucket | fail-closed | 100 | 100 | 0 | 3206.98 | 3329.21 | 2950.79 | 2286.12 |
| redis-down | redis-token-bucket | fail-open | 100 | 0 | 0 | 2961.45 | 3008.68 | 2928.59 | 2497.42 |
| redis-down | redis-token-bucket | local-fallback | 100 | 88 | 0 | 2448.50 | 2490.20 | 2412.40 | 2378.87 |
| redis-down | redis-sliding-window | fail-closed | 100 | 100 | 0 | 2283.58 | 2299.40 | 2225.01 | 2175.71 |
| redis-down | redis-sliding-window | fail-open | 100 | 0 | 0 | 3063.76 | 3067.63 | 2981.58 | 2154.83 |
| redis-down | redis-sliding-window | local-fallback | 100 | 88 | 0 | 3540.12 | 3554.33 | 3446.66 | 2261.34 |
| redis-recovered | redis-fixed-window | fail-closed | 100 | 88 | 0 | 2218.77 | 2223.20 | 2143.45 | 2053.46 |
| redis-recovered | redis-fixed-window | fail-open | 100 | 100 | 0 | 247.40 | 278.12 | 159.55 | 91.91 |
| redis-recovered | redis-fixed-window | local-fallback | 100 | 88 | 0 | 2326.04 | 2360.29 | 2291.18 | 2074.95 |
| redis-recovered | redis-token-bucket | fail-closed | 100 | 88 | 0 | 756.28 | 804.80 | 608.69 | 373.12 |
| redis-recovered | redis-token-bucket | fail-open | 100 | 100 | 0 | 526.11 | 542.29 | 491.17 | 485.57 |
| redis-recovered | redis-token-bucket | local-fallback | 100 | 99 | 0 | 184.78 | 214.50 | 139.28 | 65.26 |
| redis-recovered | redis-sliding-window | fail-closed | 100 | 100 | 0 | 220.43 | 251.65 | 169.31 | 133.36 |
| redis-recovered | redis-sliding-window | fail-open | 100 | 0 | 0 | 1082.92 | 1114.40 | 907.14 | 284.13 |
| redis-recovered | redis-sliding-window | local-fallback | 100 | 100 | 0 | 158.65 | 180.99 | 93.61 | 76.96 |

## HTTP Status Totals

### Strategy Matrix Manual 153953
- `200`: 6951
- `401`: 0
- `429`: 9
- `5xx`: 340
- `error`: 3500

### Burst 145813
- `200`: 60
- `429`: 420

### Fault 145813
- `200`: 576
- `429`: 2024
- `error`: 100

## Research Question Coverage

- Baseline load: answerable only partially. Redis fixed-window and token-bucket have full HTTP 200 samples; in-memory baseline is unusable because all 600 measured records are client errors; sliding-window baseline has mostly 200 with small 429/error contamination.
- Backend delay: answerable partially. Fixed-window and token-bucket have strong successful samples in `delay-100`; sliding-window has mixed 200/503/error; in-memory delay-100 is unusable for successful-path analysis because all 600 measured records are client errors.
- Overload: answerable partially. Fixed-window and token-bucket have full successful samples; sliding-window has mixed 200/error/503/429; in-memory overload is unusable for successful-path analysis because all 1500 measured records are client errors.
- Burst load: answerable with valid evidence, but route quota `12/60` means the results primarily show burst rejection behavior rather than backend successful-path capacity.
- Redis failure: answerable with valid fault evidence, with explicit handling for client timeouts/errors and sparse backend samples under rejection/failure.

## Main Interpretive Findings

1. Authentication and route setup remain the dominant validity risk. Runs with HTTP 401 are excluded entirely from performance analysis.
2. Under burst traffic, every strategy produced many HTTP 429 responses because the active route limit is low. This makes burst evidence useful for rejection behavior, not backend-latency success paths.
3. Under Redis failure, fail-open/local-fallback policies can preserve completed responses but may introduce large client and rate-limiter tails in some rows; client errors/timeouts remain a separate population.
4. In successful-path strategy data, backend and gateway tail latencies vary strongly by scenario and strategy, but the matrix is not complete enough for a clean four-strategy ranking.

## Threats To Validity

- Strategy matrix evidence is PARTIALLY_VALID, not fully valid.
- Client errors/timeouts include `clientLatencyMs` but lack gateway/backend/rate-limiter headers.
- Completed HTTP 5xx responses may include gateway/backend latency headers but are failure-path samples.
- `GatewayRequestContext.rateLimitKey` uses tenant+route for authenticated requests, so `X-Forwarded-For` does not isolate quota for authenticated traffic.
- Benchmark scripts use fixed-count concurrent execution, not a paced arrival-rate model.
- Docker/JVM startup, local scheduling, and shared host resources can affect p95/p99.

## Limitations

- No chart generation in this phase.
- No LaTeX artifact was located to update.
- No source, route, tenant, Docker, or database state was modified in Gate 6B report writing.
- Existing benchmark route/tenant cleanup remains a separate operational task.

## Recommended Next Step

Use this analysis report as the source for the scientific write-up. If a complete four-strategy success-path matrix is required, rerun with a verified high-quota route and confirm each strategy/scenario has nonzero HTTP 200 samples before analysis.

## Git State At Report Generation

- `git log -1`: `79fa251 Add tail latency benchmark instrumentation and report`

```text
M api-gateway/Dockerfile
 M api-gateway/pom.xml
 M mock-backend/Dockerfile
 M mock-backend/pom.xml
```
