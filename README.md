# Distributed API Gateway Latency Evaluation

Project này mô phỏng một API Gateway trong distributed system và đo ảnh hưởng của backend latency, Redis latency, thuật toán rate limiting và fault policy lên gateway.

## Thành phần chính

- `api-gateway`: Spring Boot gateway proxy `/api/v1/**` sang backend, áp dụng rate limiting và trả latency headers.
- `mock-backend-service/mock-backend-service`: backend mô phỏng có tham số `delayMs`.
- `redis`: state store cho các rate limiter phân tán.
- Python tools:
  - `gateway_latency_benchmark.py`: benchmark một gateway.
  - `run_latency_experiments.py`: chạy ma trận strategy/scenario.
  - `burst_behavior_experiment.py`: đo burst behavior quanh ranh giới window.
  - `plot_latency_report.py`: tạo CSV và biểu đồ từ manifest.

## Rate limiter strategies

- `in-memory`
- `redis-fixed-window`
- `redis-sliding-window`
- `redis-token-bucket`

Redis failure policy:

- `fail-closed`
- `fail-open`
- `local-fallback`

## Latency headers

- `X-RateLimit-Latency-Ms`
- `X-Backend-Latency-Ms`
- `X-Gateway-Latency-Ms`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`

## Chuẩn bị

```powershell
python -m pip install -r requirements.txt
Copy-Item .env.example .env
```

Điền token Redis/OpenAI trong `.env` nếu bạn cần dùng các phần đó. `.env` không được commit.

## Chạy test

```powershell
.\scripts\run_maven_tests.ps1
```

Nếu Docker chưa chạy, Testcontainers Redis sẽ skip. Unit test vẫn chạy.

## Chạy bằng Docker Compose

```powershell
docker compose up --build
```

Endpoint mặc định:

| Strategy | URL |
| --- | --- |
| `redis-sliding-window` | `http://localhost:8080/api/v1/hello` |
| `redis-fixed-window` | `http://localhost:8082/api/v1/hello` |
| `redis-token-bucket` | `http://localhost:8083/api/v1/hello` |
| `in-memory` | `http://localhost:8084/api/v1/hello` |

## Chạy benchmark và so sánh

Benchmark đơn:

```powershell
python gateway_latency_benchmark.py --url http://localhost:8080/api/v1/hello --delay-ms 100 --requests 300 --concurrency 30
```

Chạy ma trận so sánh:

```powershell
python run_latency_experiments.py
python plot_latency_report.py --manifest reports/manifest.json
```

Burst experiment:

```powershell
python burst_behavior_experiment.py --label redis-fixed-window --url http://localhost:8082/api/v1/hello --align-to-window --output reports/burst-fixed-window.json
```

## Tài liệu

- `PROJECT_PLAN.md`: kế hoạch và hướng nghiên cứu.
- `LATENCY_EVALUATION.md`: protocol chạy latency evaluation.
- `EXPERIMENT_REPORT.md`: template báo cáo kết quả, không có số liệu giả.
- `REPRODUCIBILITY.md`: checklist để chạy lại thí nghiệm.
- `README_APPENDIX.md`: ghi chú diễn giải thêm cho report cũ.
