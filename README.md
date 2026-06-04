# Distributed API Gateway Latency Evaluation

Project này mô phỏng một API Gateway trong distributed system để đo và so sánh ảnh hưởng của:

- backend latency
- Redis latency
- thuật toán rate limiting
- Redis fault policy
- burst traffic và overload

Mục tiêu chính là tạo dữ liệu thực nghiệm có thể dùng cho báo cáo học thuật, không điền số liệu nếu chưa chạy experiment thật.

## 1. Kiến trúc

Các thành phần chính:

- `api-gateway`: Spring Boot gateway, proxy `/api/v1/**` sang backend, áp dụng rate limiting và trả latency headers.
- `mock-backend-service/mock-backend-service`: backend mô phỏng có tham số `delayMs`.
- `redis`: state store cho các rate limiter phân tán.
- Python experiment tools:
  - `gateway_latency_benchmark.py`: benchmark một gateway endpoint.
  - `run_latency_experiments.py`: chạy ma trận strategy/scenario/fault-policy.
  - `plot_latency_report.py`: tạo CSV và biểu đồ từ manifest.
  - `burst_behavior_experiment.py`: đo burst behavior quanh ranh giới window.
  - `fault_tolerance_experiment.py`: đo hành vi khi Redis healthy/down/recovered.

## 2. Rate Limiter Strategies

| Strategy | Mô tả | Port mặc định |
| --- | --- | ---: |
| `redis-sliding-window` | Redis sorted set theo timestamp request | `8080` |
| `redis-fixed-window` | Redis counter theo fixed window, atomic bằng Lua | `8082` |
| `redis-token-bucket` | Redis hash lưu token và refill timestamp | `8083` |
| `in-memory` | Local limiter trong từng gateway instance | `8084` |

Redis fault policies:

| Policy | Hành vi khi Redis unreachable |
| --- | --- |
| `fail-closed` | Reject request để bảo vệ quota |
| `fail-open` | Allow request để giữ availability |
| `local-fallback` | Dùng local in-memory limiter tạm thời |

## 3. Latency Headers

Gateway trả các headers sau để benchmark phân tách latency:

- `X-RateLimit-Latency-Ms`
- `X-Backend-Latency-Ms`
- `X-Gateway-Latency-Ms`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`
- `Retry-After` khi bị `429`

## 4. Yêu Cầu Môi Trường

Cần có:

- Java 17 hoặc mới hơn
- Python 3.10 hoặc mới hơn
- Docker Desktop
- PowerShell trên Windows

Kiểm tra nhanh:

```powershell
java -version
python --version
docker version
docker compose version
```

## 5. Chuẩn Bị Lần Đầu

Đứng tại thư mục root của repo:

```powershell
cd C:\Users\ASUS\Downloads\Distributed-System-API-Gateway
```

Tạo file `.env`:

```powershell
Copy-Item .env.example .env
```

Cài Python dependencies:

```powershell
python -m pip install -r requirements.txt
```

Ghi chú:

- `.env` đã nằm trong `.gitignore`, không commit file này.
- Nếu chỉ chạy Docker Compose local thì có thể giữ `BACKEND_BASE_URL=http://mock-backend:8081`.
- Nếu chạy gateway bằng Maven ngoài Docker, đổi `BACKEND_BASE_URL=http://localhost:8081`.

## 6. Chạy Test

Chạy toàn bộ Maven tests:

```powershell
.\scripts\run_maven_tests.ps1
```

Kỳ vọng:

- `api-gateway` unit tests pass.
- `mock-backend-service` context test pass.
- Testcontainers Redis sẽ chạy nếu Docker environment được Java/Testcontainers nhận diện.
- Nếu Testcontainers không nhận Docker, các Redis integration tests sẽ skip; đây là vấn đề môi trường, không phải lỗi compile.

## 7. Build Docker Images

Build toàn bộ images từ `docker-compose.yml`:

```powershell
docker compose build
```

Kiểm tra image:

```powershell
docker image ls --format "table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}" | Select-String "api-gateway|mock-backend-service"
```

Images chính:

- `api-gateway:latest`
- `mock-backend-service:latest`

## 8. Chạy Hệ Thống Bằng Docker Compose

Khởi động toàn bộ stack:

```powershell
docker compose up -d
```

Kiểm tra trạng thái:

```powershell
docker compose ps
```

Xem logs nếu cần:

```powershell
docker compose logs -f redis
docker compose logs -f mock-backend
docker compose logs -f api-gateway-redis-sliding-window
```

Dừng hệ thống:

```powershell
docker compose down
```

Dừng và xóa volume Redis:

```powershell
docker compose down -v
```

## 9. Kiểm Tra Endpoint

Mock backend:

```powershell
curl.exe -i "http://localhost:8081/api/v1/hello?delayMs=100"
```

Gateway endpoints:

```powershell
curl.exe -i "http://localhost:8080/api/v1/hello?delayMs=100"
curl.exe -i "http://localhost:8082/api/v1/hello?delayMs=100"
curl.exe -i "http://localhost:8083/api/v1/hello?delayMs=100"
curl.exe -i "http://localhost:8084/api/v1/hello?delayMs=100"
```

Internal latency snapshots:

```powershell
curl.exe "http://localhost:8080/internal/latency/report"
curl.exe "http://localhost:8082/internal/latency/report"
curl.exe "http://localhost:8083/internal/latency/report"
curl.exe "http://localhost:8084/internal/latency/report"
```

Redis Commander:

```text
http://localhost:8085
```

## 10. Port Mapping Đầy Đủ

Strategy matrix:

| Target | URL |
| --- | --- |
| `redis-sliding-window` | `http://localhost:8080/api/v1/hello` |
| `redis-fixed-window` | `http://localhost:8082/api/v1/hello` |
| `redis-token-bucket` | `http://localhost:8083/api/v1/hello` |
| `in-memory` | `http://localhost:8084/api/v1/hello` |

Fault-policy matrix:

| Target | URL |
| --- | --- |
| `redis-fixed-window@fail-open` | `http://localhost:8090/api/v1/hello` |
| `redis-fixed-window@local-fallback` | `http://localhost:8091/api/v1/hello` |
| `redis-token-bucket@fail-open` | `http://localhost:8092/api/v1/hello` |
| `redis-token-bucket@local-fallback` | `http://localhost:8093/api/v1/hello` |
| `redis-sliding-window@fail-open` | `http://localhost:8094/api/v1/hello` |
| `redis-sliding-window@local-fallback` | `http://localhost:8095/api/v1/hello` |

`fail-closed` dùng các port strategy mặc định: `8080`, `8082`, `8083`.

## 11. Benchmark Đơn

Benchmark một endpoint:

```powershell
python gateway_latency_benchmark.py `
  --url http://localhost:8080/api/v1/hello `
  --delay-ms 100 `
  --requests 300 `
  --concurrency 30 `
  --client-id 198.18.0.10 `
  --warmup-requests 20 `
  --output reports/single/sliding-delay-100.json
```

Ý nghĩa một số tham số:

- `--client-id`: gửi qua `X-Forwarded-For` để cô lập rate-limit key.
- `--warmup-requests`: request warm-up không tính vào kết quả đo chính.
- `--delay-ms`: delay giả lập ở mock backend.

## 12. Chạy Strategy Matrix

Chạy smoke test nhỏ trước:

```powershell
python run_latency_experiments.py `
  --strategy-matrix `
  --scenarios baseline `
  --trials 1 `
  --warmup-requests 5 `
  --output-dir reports/smoke
```

Vẽ biểu đồ smoke test:

```powershell
python plot_latency_report.py --manifest reports/smoke/manifest.json
```

Chạy full strategy matrix:

```powershell
python run_latency_experiments.py `
  --strategy-matrix `
  --scenarios baseline,delay-100,delay-500,overload `
  --trials 3 `
  --warmup-requests 20 `
  --output-dir reports/strategy-matrix
```

Vẽ biểu đồ:

```powershell
python plot_latency_report.py `
  --manifest reports/strategy-matrix/manifest.json `
  --metric gatewayP95Ms `
  --secondary-metric rejectionRate
```

Artifacts chính:

- `reports/strategy-matrix/manifest.json`
- `reports/strategy-matrix/latency_comparison.csv`
- `reports/strategy-matrix/latency_comparison.png`
- `reports/strategy-matrix/ratelimiter_overhead.png`
- `reports/strategy-matrix/latency_components.png`
- `reports/strategy-matrix/heatmap_gatewayP95Ms.png`

## 13. Chạy Fault-Policy Matrix

So sánh policy khi Redis vẫn healthy:

```powershell
python run_latency_experiments.py `
  --fault-policy-matrix `
  --strategies redis-fixed-window,redis-token-bucket,redis-sliding-window `
  --scenarios baseline,overload `
  --trials 3 `
  --warmup-requests 20 `
  --output-dir reports/fault-policy
```

Vẽ biểu đồ:

```powershell
python plot_latency_report.py --manifest reports/fault-policy/manifest.json
```

Fault-injection thật: đo khi Redis healthy, Redis down, Redis recovered:

```powershell
python fault_tolerance_experiment.py `
  --docker-redis-container gateway-redis `
  --strategies redis-fixed-window,redis-token-bucket `
  --requests 100 `
  --concurrency 20 `
  --warmup-requests 10 `
  --output reports/fault-tolerance/report.json
```

Lưu ý: lệnh này sẽ stop/start container Redis nếu dùng `--docker-redis-container gateway-redis`.

## 14. Chạy Burst Experiment

Chạy cho tất cả strategy:

```powershell
python burst_behavior_experiment.py `
  --multi-strategy `
  --concurrent `
  --align-to-window `
  --burst-size 60 `
  --output-dir reports/burst
```

Chạy riêng fixed-window:

```powershell
python burst_behavior_experiment.py `
  --label redis-fixed-window `
  --url http://localhost:8082/api/v1/hello `
  --concurrent `
  --align-to-window `
  --burst-size 60 `
  --output reports/burst/burst-fixed-window.json
```

## 15. Cách Đọc Kết Quả

Trong report JSON/CSV, các metric quan trọng:

- `clientP95Ms`: latency client quan sát.
- `gatewayP95Ms`: latency tổng bên trong gateway.
- `backendP95Ms`: latency gateway proxy sang backend.
- `rateLimiterP95Ms`: overhead của rate limiter.
- `throughputRequestsPerSecond`: throughput đo được.
- `rejectionRate`: tỷ lệ `429`.

Diễn giải nhanh:

- Nếu `rateLimiterP95Ms` cao nhưng `backendP95Ms` thấp, bottleneck nằm ở Redis/rate limiter.
- Nếu `backendP95Ms` gần bằng `gatewayP95Ms`, backend latency chi phối request.
- Nếu `clientP95Ms` cao hơn nhiều so với `gatewayP95Ms`, có thể có client-side queueing hoặc network overhead.
- Fixed window có thể cho boundary burst quanh ranh giới window; dùng burst experiment để chứng minh.

## 16. Điền Báo Cáo

Sau khi có dữ liệu thật:

1. Mở `reports/strategy-matrix/latency_comparison.csv`.
2. Mở các biểu đồ PNG trong `reports/strategy-matrix/`.
3. Điền số liệu vào `EXPERIMENT_REPORT.md`.
4. Không điền kết luận định lượng nếu run có nhiều lỗi connection hoặc chỉ chạy một trial.

## 17. Chạy Local Bằng Maven Nếu Không Dùng Docker

Terminal 1, chạy backend:

```powershell
$env:BACKEND_BASE_URL="http://localhost:8081"
cd mock-backend-service\mock-backend-service
.\mvnw.cmd spring-boot:run
```

Terminal 2, chạy Redis local hoặc Docker Redis:

```powershell
docker run --name gateway-redis-local -p 6379:6379 redis:7-alpine
```

Terminal 3, chạy gateway:

```powershell
$env:BACKEND_BASE_URL="http://localhost:8081"
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
$env:RATE_LIMIT_STRATEGY="redis-sliding-window"
cd api-gateway
.\mvnw.cmd spring-boot:run
```

Sau đó gọi:

```powershell
curl.exe -i "http://localhost:8080/api/v1/hello?delayMs=100"
```

## 18. Troubleshooting

Nếu port bị chiếm:

```powershell
netstat -ano | findstr :8080
```

Nếu muốn reset Redis state:

```powershell
docker exec gateway-redis redis-cli FLUSHALL
```

Nếu containers không lên:

```powershell
docker compose logs --tail=100
```

Nếu Testcontainers skip trong Maven test:

- Kiểm tra Docker Desktop đang chạy.
- Kiểm tra `docker version`.
- Đây không chặn việc build/chạy Docker Compose, nhưng integration tests Redis thật sẽ không chạy trong Maven.

Nếu muốn rebuild sạch:

```powershell
docker compose down -v
docker compose build --no-cache
docker compose up -d
```

## 19. Tài Liệu Liên Quan

- `PROJECT_PLAN.md`: kế hoạch và hướng nghiên cứu.
- `LATENCY_EVALUATION.md`: protocol đánh giá latency.
- `EXPERIMENT_REPORT.md`: template báo cáo kết quả, không có số liệu giả.
- `REPRODUCIBILITY.md`: checklist tái lập thí nghiệm.
- `README_APPENDIX.md`: ghi chú diễn giải thêm.
