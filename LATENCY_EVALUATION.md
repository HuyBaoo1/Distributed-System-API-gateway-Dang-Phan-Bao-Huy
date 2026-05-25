# API Gateway Latency Evaluation

Mục tiêu mới của project là đo và giải thích độ trễ trong một gateway phân tán:

- `rate_limiter`: thời gian gateway tiêu tốn để kiểm tra quota, đặc biệt khi dùng Redis.
- `backend_proxy`: thời gian proxy request từ gateway sang backend.
- `gateway_total`: thời gian end-to-end bên trong gateway cho một request `/api/v1/**`.
- `clientObserved`: thời gian client nhìn thấy khi gọi gateway bằng script benchmark.

## Luồng thí nghiệm

Chạy toàn bộ Maven test:

```powershell
.\scripts\run_maven_tests.ps1
```

Testcontainers Redis sẽ tự skip nếu Docker chưa khả dụng. Để chạy integration test thật:

```powershell
# Bật Docker Desktop trước
.\scripts\run_maven_tests.ps1
```

1. Chạy mock backend:

   ```powershell
   . .\scripts\load_env.ps1
   cd mock-backend-service\mock-backend-service
   .\mvnw.cmd spring-boot:run
   ```

2. Chạy gateway:

   ```powershell
   . .\scripts\load_env.ps1
   cd api-gateway
   .\mvnw.cmd spring-boot:run
   ```

3. Gọi thử gateway:

   ```powershell
   curl.exe -i "http://localhost:8080/api/v1/hello?delayMs=100"
   ```

   Các header quan trọng:

   - `X-RateLimit-Latency-Ms`
   - `X-Backend-Latency-Ms`
   - `X-Gateway-Latency-Ms`
   - `X-RateLimit-Remaining`

4. Chạy benchmark:

   ```powershell
   python -m pip install -r requirements.txt
   python gateway_latency_benchmark.py --delay-ms 100 --requests 300 --concurrency 30
   ```

5. Xem snapshot nội bộ của gateway:

   ```powershell
   curl.exe "http://localhost:8080/internal/latency/report"
   ```

6. Chạy bộ experiment mặc định:

   ```powershell
   python run_latency_experiments.py
   ```

   Kết quả được lưu trong `reports/`:

   - `baseline.json`
   - `delay-100.json`
   - `delay-500.json`
   - `overload.json`
   - `manifest.json`

## Kịch bản nên so sánh

- Baseline: `delayMs=0`, `RATE_LIMIT_STRATEGY=in-memory`.
- Redis overhead: `delayMs=0`, `RATE_LIMIT_STRATEGY=redis-sliding-window`.
- Backend chậm vừa: `delayMs=100`.
- Backend chậm nặng: `delayMs=500`.
- Rate limit pressure: giảm `RATE_LIMIT_REQUESTS_PER_MINUTE`, tăng `--concurrency`, quan sát tỷ lệ `429`.

## Cách đọc kết quả

- Nếu `X-RateLimit-Latency-Ms` tăng trong khi `X-Backend-Latency-Ms` thấp, Redis/rate limiter là điểm nghẽn.
- Nếu `X-Backend-Latency-Ms` gần bằng `X-Gateway-Latency-Ms`, gateway chủ yếu bị downstream latency chi phối.
- Nếu `clientObserved` cao hơn nhiều so với `gatewayHeader`, phần chênh lệch thường đến từ network client, queueing ở client, hoặc connection reuse.
- Khi concurrency tăng, p95/p99 quan trọng hơn average vì tail latency mới thể hiện ảnh hưởng thật lên gateway.
