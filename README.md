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

Gateway và các công cụ benchmark đọc cấu hình từ environment variables hoặc file `.env`.
Sao chép `.env.example` thành `.env` và điền giá trị phù hợp trước khi chạy.

### Các biến quan trọng

```properties
REDIS_API_TOKEN=
REDIS_HOST=localhost
REDIS_PORT=6379
OPENAI_API_KEY=
BACKEND_BASE_URL=http://localhost:8081
GATEWAY_URL=http://localhost:8080/api/v1/hello
GATEWAY_URL_ALT=
RATE_LIMIT_STRATEGY=redis-sliding-window
RATE_LIMIT_REQUESTS_PER_MINUTE=60
RATE_LIMIT_WINDOW_SECONDS=60
BENCHMARK_REQUESTS=100
BENCHMARK_CONCURRENCY=10
BENCHMARK_TIMEOUT_SECONDS=10
BENCHMARK_OUTPUT=latency_report.json
```

## Cách chạy project

Project này gồm ba thành phần chính:

1. `mock-backend-service`: backend demo chạy bằng Spring Boot.
2. `api-gateway`: gateway chạy bằng Spring Boot, có filter rate limiting và latency headers.
3. Python helper scripts: benchmark và so sánh latency.

### 1. Chuẩn bị môi trường

1. Tạo file `.env` từ `.env.example`:

```powershell
Copy-Item .env.example .env
```

2. Điền giá trị trong `.env` như `REDIS_HOST`, `REDIS_PORT`, `GATEWAY_URL`, `BACKEND_BASE_URL`, và nếu cần `REDIS_API_TOKEN`.

3. Load biến môi trường vào PowerShell:

```powershell
. .\scripts\load_env.ps1
```

4. Cài Python dependencies nếu chưa có:

```powershell
python -m pip install -r requirements.txt
```

### 2. Chạy backend mock

```powershell
cd mock-backend-service\mock-backend-service
.\mvnw.cmd spring-boot:run
```

Mặc định backend sẽ chạy trên cổng `8081`.

### 3. Chạy gateway

Mở terminal khác, load lại `.env`, rồi chạy:

```powershell
cd api-gateway
.\mvnw.cmd spring-boot:run
```

Gateway mặc định chạy trên cổng `8080`.

### 4. Gọi thử endpoint

```powershell
curl.exe -i "http://localhost:8080/api/v1/hello?delayMs=100"
```

Kiểm tra header trả về để hiểu tính toán latency:

- `X-RateLimit-Latency-Ms`: thời gian xử lý rate limiter.
- `X-Backend-Latency-Ms`: thời gian backend xử lý request.
- `X-Gateway-Latency-Ms`: thời gian tổng hợp gateway.

### 5. Benchmark gateway latency

```powershell
python gateway_latency_benchmark.py --delay-ms 100 --requests 300 --concurrency 30
```

### 6. So sánh chiến lược

Để so sánh `in-memory` và `redis-sliding-window`, chạy hai gateway cùng lúc trên hai cổng khác nhau,
rồi dùng script so sánh:

```powershell
python compare_strategies.py --url-a http://localhost:8080/api/v1/hello --label-a redis --url-b http://localhost:8082/api/v1/hello --label-b inmemory --requests 200 --concurrency 20 --delay-ms 100 --output compare_report.json
```

### 7. Đo latency Redis cluster qua API provider

```powershell
python redis_cluster_latency_monitor.py --api-base-url https://api.your-redis-provider.com --cluster-id your-cluster-id
```

## Docker: build và chạy bằng container

Nếu muốn chạy mỗi service trong container, repository đã cung cấp `Dockerfile` trong các module.

1. Build image cho `mock-backend-service`:

```powershell
cd mock-backend-service\mock-backend-service
docker build -t mock-backend-service:latest -f Dockerfile .
```

2. Build image cho `api-gateway`:

```powershell
cd api-gateway
docker build -t api-gateway:latest -f Dockerfile .
```

3. Chạy container (với `.env` đã load hoặc truyền biến môi trường trực tiếp):

```powershell
# backend
docker run -d --name mock-backend -p 8081:8081 --env-file ../.env mock-backend-service:latest

# gateway
docker run -d --name api-gateway -p 8080:8080 --env-file ../.env api-gateway:latest
```

Lưu ý:
- Các `Dockerfile` đã được đặt tên chuẩn `Dockerfile` (không phải `DOCKERFILE`) và expose cổng phù hợp (gateway 8080, backend 8081).
- Nếu build thất bại trong stage Maven, đảm bảo Docker daemon có đủ tài nguyên, và `mvnw`/`.mvn` tồn tại trong thư mục để sử dụng Maven wrapper.
- Bạn có thể thêm `-e` flags vào `docker run` để override biến môi trường nếu cần.


## Tại sao không có `main.py` để chạy tất cả?

Project này là một hệ thống đa thành phần:

- `mock-backend-service` và `api-gateway` là các ứng dụng Java/Spring Boot, khởi động bằng `mvnw.cmd spring-boot:run` chứ không phải bằng Python.
- Các công cụ đánh giá latency (`gateway_latency_benchmark.py`, `compare_strategies.py`, `redis_cluster_latency_monitor.py`) là script Python, dùng để thu thập và phân tích số liệu.
- Việc giữ mỗi thành phần tách biệt giúp mô phỏng đúng kiến trúc distributed system và dễ dàng mở rộng mỗi phần riêng lẻ.


## Tham khảo thêm

- `LATENCY_EVALUATION.md` cho phân tích kết quả.
- `compare_strategies.py` để so sánh trực tiếp hai deployment.
- `redis_cluster_latency_monitor.py` để đo latency Redis cluster qua API provider.
