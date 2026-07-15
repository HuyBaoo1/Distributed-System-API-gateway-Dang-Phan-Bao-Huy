# GateShield Benchmarks

The original latency and rate limiter benchmark scripts are preserved for local experiments. They write generated output under `reports/`, which is ignored by Git.

## Setup

```bash
pip install -r requirements.txt
docker compose --profile experiments up --build
```

The default MVP gateway runs on port `8080`. Extra strategy gateways are available only when the `experiments` Compose profile is enabled.

Create a tenant and route first, then export the tenant key for benchmark requests:

```bash
export BENCHMARK_API_KEY="<tenant-api-key>"
```

## Useful Commands

Single target latency run:

```bash
python gateway_latency_benchmark.py --url http://localhost:8080/api/v1/hello --requests 200 --concurrency 20
```

Strategy matrix:

```bash
python run_latency_experiments.py --strategy-matrix
```

Burst behavior:

```bash
python burst_behavior_experiment.py --multi-strategy --concurrent --align-to-window
```

Plot reports:

```bash
python plot_latency_report.py --manifest reports/manifest.json
```

## Notes

- Generated files in `reports/` are local artifacts.
- API calls through GateShield require a tenant API key. For product smoke testing, use `scripts/smoke_test.ps1` or `scripts/smoke_test.sh`.
- For the three-replica distributed quota proof, use `scripts/distributed_rate_limit_test.py` and `docs/DISTRIBUTED_RATE_LIMITING.md`.
- The external Redis-provider monitor and unrelated ASR evaluation script were removed from the MVP cleanup.
