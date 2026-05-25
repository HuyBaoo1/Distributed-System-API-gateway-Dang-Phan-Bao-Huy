# Kế hoạch dự án Distributed API Gateway Latency Evaluation

## Mục tiêu nghiên cứu

- Đo độ trễ của API Gateway theo từng thành phần: rate limiter, proxy backend, và tổng thời gian xử lý trong gateway.
- Đánh giá backend latency và Redis latency ảnh hưởng thế nào đến p50/p95/p99, throughput, và tỷ lệ `429`.
- So sánh `in-memory` rate limiter với `redis-sliding-window` để thấy trade-off giữa độ chính xác phân tán và chi phí latency.
- Chuẩn hóa cách chạy thí nghiệm bằng `.env`, script benchmark, response headers, và endpoint snapshot.

## Kiến trúc hiện tại

1. `mock-backend-service`
   - Backend mô phỏng chạy cổng `8081`.
   - Endpoint `GET /api/v1/hello?delayMs=100` tạo downstream latency có kiểm soát.
2. `api-gateway`
   - Nhận request tại `http://localhost:8080/api/v1/**`.
   - Áp dụng rate limit trước khi proxy sang backend.
   - Gắn header latency vào response:
     - `X-RateLimit-Latency-Ms`
     - `X-Backend-Latency-Ms`
     - `X-Gateway-Latency-Ms`
3. `gateway_latency_benchmark.py`
   - Bắn tải đồng thời vào gateway.
   - Tổng hợp `clientObserved`, `gatewayHeader`, `backendHeader`, `rateLimiterHeader`.
4. `redis_cluster_latency_monitor.py`
   - Đo latency Redis provider API hoặc ping trực tiếp Redis endpoints.

## Thành phần đã bổ sung

- `api-gateway/src/main/java/com/example/apigateway/service/LatencyMetricsService.java`
- `api-gateway/src/main/java/com/example/apigateway/controller/LatencyMetricsController.java`
- `mock-backend-service/mock-backend-service/src/main/java/com/example/mock_backend_service/MockLatencyController.java`
- `gateway_latency_benchmark.py`
- `run_latency_experiments.py`
- `LATENCY_EVALUATION.md`
- `.env`
- `.env.example`
- Testcontainers Redis integration test cho `RedisSlidingWindowRateLimiterService`

## Kịch bản nghiên cứu chính

1. Baseline không delay:
   - `BACKEND_DELAY_MS=0`
   - `RATE_LIMIT_STRATEGY=in-memory`
2. Đo chi phí Redis:
   - `BACKEND_DELAY_MS=0`
   - `RATE_LIMIT_STRATEGY=redis-sliding-window`
3. Downstream latency:
   - `BACKEND_DELAY_MS=100`, `500`, `1000`
4. Rate limit pressure:
   - Giảm `RATE_LIMIT_REQUESTS_PER_MINUTE`.
   - Tăng `BENCHMARK_CONCURRENCY`.
   - Quan sát `429`, `Retry-After`, p95/p99.

## Lệnh chạy nhanh

```powershell
python -m pip install -r requirements.txt
python gateway_latency_benchmark.py --delay-ms 100 --requests 300 --concurrency 30
python run_latency_experiments.py
curl.exe "http://localhost:8080/internal/latency/report"
```

Chi tiết nằm trong `LATENCY_EVALUATION.md`.
