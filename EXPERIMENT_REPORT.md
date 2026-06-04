# Báo cáo thí nghiệm latency API Gateway

File này là template báo cáo học thuật. Các ô `TBD` chỉ được điền sau khi chạy thí nghiệm thật bằng script trong repo.

## 1. Câu hỏi nghiên cứu

| ID | Câu hỏi |
| --- | --- |
| RQ1 | Chiến lược rate limiting nào tạo overhead thấp nhất khi backend không có delay? |
| RQ2 | Khi backend latency tăng lên 100 ms và 500 ms, overhead của rate limiter còn ảnh hưởng đáng kể tới p95/p99 gateway latency không? |
| RQ3 | Fixed window, sliding window và token bucket khác nhau thế nào dưới burst traffic? |
| RQ4 | Khi Redis lỗi, `fail-closed`, `fail-open` và `local-fallback` đánh đổi safety/availability ra sao? |

## 2. Chiến lược so sánh

| Strategy | Mô tả | Port mặc định |
| --- | --- | ---: |
| `in-memory` | Bộ đếm local trong từng gateway instance | 8084 |
| `redis-fixed-window` | Redis counter theo fixed window, atomic bằng Lua | 8082 |
| `redis-sliding-window` | Redis sorted set theo timestamp request | 8080 |
| `redis-token-bucket` | Redis hash lưu token và thời điểm refill | 8083 |

## 3. Fault policy

| Policy | Hành vi khi Redis unreachable | Trade-off |
| --- | --- | --- |
| `fail-closed` | Reject request | Bảo vệ quota, giảm availability |
| `fail-open` | Allow request | Giữ availability, có rủi ro vượt quota |
| `local-fallback` | Dùng local in-memory limiter | Cân bằng hơn, nhưng không còn quota toàn cục |

## 4. Protocol chạy thí nghiệm

Chuẩn bị:

```powershell
python -m pip install -r requirements.txt
docker compose up --build -d
```

Chạy ma trận strategy:

```powershell
python run_latency_experiments.py --strategy-matrix --trials 3 --warmup-requests 20 --output-dir reports/strategy-matrix
```

Chạy ma trận fault policy:

```powershell
python run_latency_experiments.py --fault-policy-matrix --strategies redis-fixed-window --trials 3 --warmup-requests 20 --output-dir reports/fault-policy
```

Vẽ biểu đồ:

```powershell
python plot_latency_report.py --manifest reports/strategy-matrix/manifest.json
python plot_latency_report.py --manifest reports/fault-policy/manifest.json
```

Burst behavior:

```powershell
python burst_behavior_experiment.py --multi-strategy --concurrent --align-to-window --output-dir reports/burst
```

## 5. Kết quả strategy matrix

Điền sau khi chạy `reports/strategy-matrix/manifest.json`.

| Strategy | Scenario | Gateway p95 ms | Rate limiter p95 ms | Backend p95 ms | Throughput rps | Rejection rate |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `in-memory` | `baseline` | TBD | TBD | TBD | TBD | TBD |
| `redis-fixed-window` | `baseline` | TBD | TBD | TBD | TBD | TBD |
| `redis-sliding-window` | `baseline` | TBD | TBD | TBD | TBD | TBD |
| `redis-token-bucket` | `baseline` | TBD | TBD | TBD | TBD | TBD |

## 6. Kết quả fault policy

Điền sau khi chạy `reports/fault-policy/manifest.json` hoặc `fault_tolerance_experiment.py`.

| Strategy | Fault policy | Phase | Gateway p95 ms | Rejection rate | Error count |
| --- | --- | --- | ---: | ---: | ---: |
| `redis-fixed-window` | `fail-closed` | Redis healthy | TBD | TBD | TBD |
| `redis-fixed-window` | `fail-open` | Redis down | TBD | TBD | TBD |
| `redis-fixed-window` | `local-fallback` | Redis down | TBD | TBD | TBD |

## 7. Cách diễn giải

- Nếu `rateLimiterP95Ms` tăng trong khi `backendP95Ms` thấp, bottleneck nằm ở Redis hoặc thuật toán rate limiter.
- Nếu `backendP95Ms` gần bằng `gatewayP95Ms`, downstream latency chi phối request.
- Nếu `clientP95Ms` cao hơn nhiều so với `gatewayP95Ms`, cần xem client-side queueing, network stack hoặc OS scheduling.
- Fixed window có thể cho boundary burst quanh ranh giới window; cần dùng `burst_behavior_experiment.py` để chứng minh bằng số liệu thật.
- Token bucket phù hợp hơn khi workload có burst ngắn nhưng vẫn cần kiểm soát rate trung bình.

## 8. Threats to validity

- Docker Desktop và JVM warm-up có thể làm lệch các request đầu tiên.
- Benchmark client chạy cùng máy với service có thể gây CPU scheduling interference.
- Redis local single-node không đại diện hoàn toàn cho Redis cluster production qua network xa.
- Python `ThreadPoolExecutor` không mô phỏng đầy đủ workload production.
- Cần chạy nhiều trial trước khi kết luận định lượng.

## 9. Kết luận

Chỉ điền phần này sau khi đã có report thật. Không dùng kết luận định lượng nếu manifest có nhiều lỗi connection hoặc số trial quá ít.
