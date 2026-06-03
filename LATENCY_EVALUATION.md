# API Gateway Latency Evaluation

Project này đo và so sánh latency của API Gateway trong bối cảnh distributed system. Trọng tâm là phân biệt latency đến từ backend, gateway proxy và rate limiter.

## Thành phần latency

- `rate_limiter`: thời gian gateway kiểm tra quota.
- `backend_proxy`: thời gian gateway gọi mock backend.
- `gateway_total`: tổng thời gian xử lý bên trong gateway cho request `/api/v1/**`.
- `clientObserved`: thời gian script benchmark quan sát từ phía client.

Gateway trả về các headers chính:

- `X-RateLimit-Latency-Ms`
- `X-Backend-Latency-Ms`
- `X-Gateway-Latency-Ms`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`

## Chạy test

```powershell
.\scripts\run_maven_tests.ps1
```

Testcontainers Redis sẽ tự skip nếu Docker chưa khả dụng. Khi bật Docker Desktop, các test Redis thật sẽ chạy cho `redis-sliding-window`, `redis-fixed-window` và `redis-token-bucket`.

## Chạy hệ thống bằng Docker Compose

```powershell
docker compose up --build
```

Các gateway mặc định:

| Strategy | URL |
| --- | --- |
| `redis-sliding-window` | `http://localhost:8080/api/v1/hello` |
| `redis-fixed-window` | `http://localhost:8082/api/v1/hello` |
| `redis-token-bucket` | `http://localhost:8083/api/v1/hello` |
| `in-memory` | `http://localhost:8084/api/v1/hello` |

## Chạy benchmark đơn

```powershell
python gateway_latency_benchmark.py --url http://localhost:8080/api/v1/hello --delay-ms 100 --requests 300 --concurrency 30
```

Kết quả lưu trong `latency_report.json` hoặc file được chỉ định bằng `--output`.

## Chạy ma trận so sánh

```powershell
python run_latency_experiments.py
```

Runner đọc các biến sau từ `.env`:

- `EXPERIMENT_STRATEGIES`
- `EXPERIMENT_STRATEGY_TARGETS`
- `EXPERIMENT_INTERNAL_REPORT_URLS`
- `EXPERIMENT_SCENARIOS`
- `EXPERIMENT_OUTPUT_DIR`

Artifacts:

- `reports/manifest.json`
- `reports/<strategy>/<scenario>.json`
- `reports/<strategy>/<scenario>-gateway-snapshot.json`

## Vẽ biểu đồ

```powershell
python plot_latency_report.py --manifest reports/manifest.json
```

Output mặc định:

- `reports/latency_comparison.png`
- `reports/latency_comparison.csv`

Có thể đổi metric:

```powershell
python plot_latency_report.py --metric rateLimiterP95Ms --secondary-metric throughputRequestsPerSecond
```

## Burst experiment

Fixed window thường có boundary burst: request cuối window cũ và request đầu window mới có thể cùng được allow trong khoảng thời gian rất ngắn. Dùng script này để đo thay vì suy đoán:

```powershell
python burst_behavior_experiment.py --label redis-fixed-window --url http://localhost:8082/api/v1/hello --align-to-window --output reports/burst-fixed-window.json
python burst_behavior_experiment.py --label redis-token-bucket --url http://localhost:8083/api/v1/hello --align-to-window --output reports/burst-token-bucket.json
```

## Cách đọc kết quả

- Nếu `rateLimiterP95Ms` cao nhưng `backendP95Ms` thấp, rate limiter hoặc Redis là nguồn overhead chính.
- Nếu `backendP95Ms` gần bằng `gatewayP95Ms`, gateway bị chi phối bởi downstream latency.
- Nếu `clientP95Ms` cao hơn nhiều so với `gatewayP95Ms`, cần xem client-side queueing, network hoặc connection setup.
- Nếu `rejectionRate` tăng mạnh khi concurrency tăng, quota hoặc thuật toán rate limit đang chi phối throughput.
- So sánh `redis-fixed-window` và `redis-token-bucket` bằng burst report để tránh kết luận chỉ dựa trên average latency.

## Fault tolerance policy

Biến `RATE_LIMIT_REDIS_FAILURE_POLICY` hỗ trợ:

- `fail-closed`: Redis lỗi thì reject request.
- `fail-open`: Redis lỗi thì allow request.
- `local-fallback`: Redis lỗi thì dùng local limiter.

Khi đánh giá fault tolerance, cần ghi rõ policy đang dùng vì cùng một lỗi Redis có thể tạo ra kết quả hoàn toàn khác nhau.
