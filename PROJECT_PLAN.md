# Kế hoạch dự án Distributed API Gateway Latency Evaluation

## Mục tiêu nghiên cứu

- Đo latency của API Gateway theo từng thành phần: rate limiter, proxy backend, và tổng thời gian xử lý gateway.
- So sánh tác động của nhiều thuật toán rate limiting lên p95/p99 latency, throughput và tỷ lệ `429`.
- Tách ảnh hưởng của backend latency khỏi overhead của Redis/rate limiter.
- Chuẩn hóa cách chạy thí nghiệm bằng `.env`, Docker Compose, benchmark scripts, response headers và manifest kết quả.

## Kiến trúc hiện tại

1. `mock-backend-service`
   - Backend mô phỏng chạy port `8081`.
   - Endpoint `GET /api/v1/hello?delayMs=100` tạo downstream latency có kiểm soát.
2. `api-gateway`
   - Proxy endpoint `/api/v1/**` sang backend.
   - Áp dụng rate limiting trước khi gọi backend.
   - Gắn headers:
     - `X-RateLimit-Latency-Ms`
     - `X-Backend-Latency-Ms`
     - `X-Gateway-Latency-Ms`
3. Redis
   - Dùng làm state store cho các chiến lược rate limit phân tán.
4. Python experiment tools
   - Benchmark request concurrent.
   - Chạy ma trận strategy/scenario.
   - Ghi manifest, CSV và biểu đồ.

## Chiến lược rate limiting

- `in-memory`: local fixed window theo instance.
- `redis-fixed-window`: Redis counter theo fixed window.
- `redis-sliding-window`: Redis sorted set theo sliding window.
- `redis-token-bucket`: Redis token bucket có refill theo thời gian.

## Fault tolerance policy cho Redis

Biến cấu hình: `RATE_LIMIT_REDIS_FAILURE_POLICY`

- `fail-closed`: Redis lỗi thì reject request. An toàn cho quota, giảm availability.
- `fail-open`: Redis lỗi thì allow request. Giữ availability, có rủi ro vượt quota.
- `local-fallback`: Redis lỗi thì dùng local limiter tạm thời. Cân bằng hơn nhưng không còn quota toàn cục.

## Thành phần đã bổ sung

- `api-gateway/src/main/java/com/example/apigateway/service/RedisFixedWindowRateLimiterService.java`
- `api-gateway/src/main/java/com/example/apigateway/service/RedisTokenBucketRateLimiterService.java`
- `api-gateway/src/main/java/com/example/apigateway/service/RedisFailureHandler.java`
- `api-gateway/src/main/java/com/example/apigateway/service/LocalWindowRateLimiter.java`
- `run_latency_experiments.py`
- `burst_behavior_experiment.py`
- `plot_latency_report.py`
- `api-gateway/Dockerfile`
- `docker-compose.yml` chạy nhiều gateway theo chiến lược
- `EXPERIMENT_REPORT.md`
- `REPRODUCIBILITY.md`

## Kịch bản nghiên cứu chính

1. Baseline không backend delay:
   - Scenario: `baseline`
   - Mục tiêu: đo overhead thuần của rate limiter.
2. Backend delay vừa:
   - Scenario: `delay-100`
   - Mục tiêu: xem rate limiter còn ảnh hưởng bao nhiêu khi downstream chậm.
3. Backend delay nặng:
   - Scenario: `delay-500`
   - Mục tiêu: phân biệt backend-bound với gateway/rate-limiter-bound.
4. Overload:
   - Scenario: `overload`
   - Mục tiêu: đo p95/p99, throughput và `429` khi concurrency tăng.
5. Burst behavior:
   - Script: `burst_behavior_experiment.py`
   - Mục tiêu: quan sát burst quanh ranh giới window và token refill.

## Lệnh chạy nhanh

```powershell
python -m pip install -r requirements.txt
.\scripts\run_maven_tests.ps1
docker compose up --build
python run_latency_experiments.py
python plot_latency_report.py --manifest reports/manifest.json
```

## Ưu tiên tiếp theo

- Chạy matrix ít nhất 3 lần để có kết quả ổn định hơn.
- Thêm script tổng hợp nhiều lần chạy thành confidence interval.
- Tách network Redis ra khỏi local Docker bằng Redis remote để đo ảnh hưởng network thật.
- Thêm fault-injection scenario: tắt Redis giữa benchmark và so sánh `fail-closed`, `fail-open`, `local-fallback`.
- Bổ sung phần thảo luận kết quả thật vào `EXPERIMENT_REPORT.md` sau khi có dữ liệu.
