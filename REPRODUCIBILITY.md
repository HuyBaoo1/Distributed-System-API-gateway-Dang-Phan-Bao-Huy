# Reproducibility checklist

Mục tiêu của checklist này là giúp mỗi lần chạy latency experiment tạo ra kết quả có thể kiểm tra lại.

## Môi trường

- Java 17 hoặc mới hơn.
- Python 3.10 hoặc mới hơn.
- Docker Desktop nếu muốn chạy Testcontainers hoặc Docker Compose matrix.
- Maven wrapper đã có trong `api-gateway` và `mock-backend-service/mock-backend-service`.

## Kiểm tra trước khi chạy

```powershell
python -m pip install -r requirements.txt
.\scripts\run_maven_tests.ps1
docker compose config
```

Nếu Docker chưa chạy, các test Testcontainers Redis sẽ skip. Đây là hành vi dự kiến trên môi trường local chưa bật Docker.

## Cấu hình chính

Các biến quan trọng nằm trong `.env`:

- `RATE_LIMIT_REQUESTS_PER_MINUTE`
- `RATE_LIMIT_WINDOW_SECONDS`
- `RATE_LIMIT_REDIS_FAILURE_POLICY`
- `EXPERIMENT_STRATEGIES`
- `EXPERIMENT_STRATEGY_TARGETS`
- `EXPERIMENT_SCENARIOS`
- `BENCHMARK_REQUESTS`
- `BENCHMARK_CONCURRENCY`
- `BENCHMARK_TIMEOUT_SECONDS`

Không commit `.env` vì file này có thể chứa token thật. Dùng `.env.example` làm cấu hình mẫu.

## Chạy đầy đủ bằng Docker Compose

```powershell
docker compose up --build
```

Các endpoint mặc định:

- `redis-sliding-window`: `http://localhost:8080/api/v1/hello`
- `redis-fixed-window`: `http://localhost:8082/api/v1/hello`
- `redis-token-bucket`: `http://localhost:8083/api/v1/hello`
- `in-memory`: `http://localhost:8084/api/v1/hello`
- Redis Commander: `http://localhost:8085`

## Sinh dữ liệu so sánh

```powershell
python run_latency_experiments.py
python plot_latency_report.py --manifest reports/manifest.json
```

Artifacts chính:

- `reports/manifest.json`: manifest toàn bộ ma trận thí nghiệm.
- `reports/<strategy>/<scenario>.json`: report từng cặp strategy/scenario.
- `reports/<strategy>/<scenario>-gateway-snapshot.json`: snapshot endpoint nội bộ của gateway.
- `reports/latency_comparison.csv`: bảng so sánh dạng CSV.
- `reports/latency_comparison.png`: biểu đồ so sánh.

## Ghi chú khi báo cáo

- Không dùng số từ lần chạy bị lỗi connection hoặc có nhiều `"error"` trong report.
- Luôn ghi lại ngày chạy, cấu hình limit/window, số request, concurrency và Docker/JVM state.
- Nên chạy mỗi scenario ít nhất 3 lần nếu muốn kết luận định lượng.
- Phân biệt rõ latency từ header gateway và latency quan sát bởi client.
