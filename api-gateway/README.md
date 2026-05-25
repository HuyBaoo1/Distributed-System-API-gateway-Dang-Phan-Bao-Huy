# Distributed API Gateway

Module này là API Gateway cho project đo latency trong distributed system.

## Vai trò chính

- Proxy request `/api/v1/**` sang mock backend.
- Chặn request bằng rate limiter trước khi gọi backend.
- Hỗ trợ `in-memory` và `redis-sliding-window`.
- Gắn latency headers để đo ảnh hưởng của Redis/backend lên gateway:
  - `X-RateLimit-Latency-Ms`
  - `X-Backend-Latency-Ms`
  - `X-Gateway-Latency-Ms`

## Cấu hình

Gateway đọc cấu hình từ environment variables:

```properties
BACKEND_BASE_URL=http://localhost:8081
RATE_LIMIT_STRATEGY=redis-sliding-window
RATE_LIMIT_REQUESTS_PER_MINUTE=60
RATE_LIMIT_WINDOW_SECONDS=60
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_TOKEN=
```

File `.env.example` ở thư mục gốc repo có đầy đủ biến cho Redis, OpenAI token, và benchmark.

## Chạy local

Từ thư mục gốc repo, chạy mock backend:

```powershell
. .\scripts\load_env.ps1
cd mock-backend-service\mock-backend-service
.\mvnw.cmd spring-boot:run
```

Ở terminal khác, từ thư mục gốc repo, chạy gateway:

```powershell
. .\scripts\load_env.ps1
cd api-gateway
.\mvnw.cmd spring-boot:run
```

Gọi thử:

```powershell
curl.exe -i "http://localhost:8080/api/v1/hello?delayMs=100"
curl.exe "http://localhost:8080/internal/latency/report"
```

## Benchmark

Từ thư mục gốc repo:

```powershell
python -m pip install -r requirements.txt
python gateway_latency_benchmark.py --delay-ms 100 --requests 300 --concurrency 30
```

Xem thêm `LATENCY_EVALUATION.md`.
