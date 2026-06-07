# Latency Dashboard UI

Static dashboard for comparing gateway latency and rate-limiter overhead from real experiment artifacts.

## Input

Use one of these files after running experiments:

- `reports/<run-name>/latency_comparison.csv`
- `reports/<run-name>/manifest.json`

The dashboard does not include sample measurements.

## Open

```powershell
Invoke-Item .\ui\latency-dashboard\index.html
```

Then choose a generated CSV or JSON file from the file picker.

## Metrics

- `Gateway p95`: gateway processing latency from response headers.
- `Rate limiter p95`: rate-limiter decision overhead.
- `Backend p95`: downstream mock backend latency.
- `Client p95`: client-observed end-to-end latency.
- `429 rate`: rejected responses divided by total responses.
- `Throughput`: completed requests per second.
