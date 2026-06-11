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

Kết quả dưới đây lấy từ dashboard `Latency & Rate Limiter Comparison`, scenario `baseline`, workload 200 request với concurrency 20.

| Strategy | Scenario | Gateway p95 ms | Rate limiter p95 ms | Backend p95 ms | Throughput rps | Rejection rate |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `redis-sliding-window` | `baseline` | 82.60 | 21.29 | 49.11 | 564.4 | 70.0% |
| `in-memory` | `baseline` | 252.89 | 22.76 | 119.77 | 306.3 | 70.0% |
| `redis-token-bucket` | `baseline` | 1114.05 | 963.93 | 93.68 | 131.3 | 69.5% |
| `redis-fixed-window` | `baseline` | 1355.45 | 1199.77 | 85.54 | 113.7 | 70.0% |

### So sánh thuật toán rate limiter

`redis-sliding-window` cho kết quả tốt nhất trong lần đo baseline: gateway p95 thấp nhất, rate limiter p95 thấp nhất và throughput cao nhất. Với rejection rate khoảng 70%, thuật toán này vẫn chặn phần lớn request vượt quota nhưng không tạo overhead lớn lên gateway. Đây là lựa chọn cân bằng nhất khi cần quota global cho nhiều gateway instance.

`in-memory` đứng thứ hai về latency tổng thể. Rate limiter p95 gần tương đương `redis-sliding-window`, nhưng gateway/client p95 cao hơn. Điểm yếu chính của `in-memory` không nằm ở tốc độ local mà ở tính phân tán: khi scale nhiều gateway instance, mỗi instance giữ bộ đếm riêng nên quota không còn global.

`redis-token-bucket` có rejection rate thấp nhất một chút, 69.5%, nhưng rate limiter p95 lên tới 963.93 ms. Vì backend p95 chỉ 93.68 ms, tail latency chủ yếu đến từ bước kiểm tra quota hoặc Redis. Token bucket vẫn phù hợp workload có burst ngắn, nhưng implementation hiện tại cần kiểm tra Redis latency, Lua script, connection pool và contention.

`redis-fixed-window` là chậm nhất trong bộ số liệu này, với gateway p95 1355.45 ms và rate limiter p95 1199.77 ms. Fixed window thường đơn giản, nhưng có rủi ro boundary burst quanh ranh giới window. Trong kết quả hiện tại, vấn đề nổi bật hơn là overhead rate limiter quá cao so với backend.

Tóm lại, nếu ưu tiên latency và throughput trong môi trường distributed API gateway, `redis-sliding-window` đang là lựa chọn tốt nhất. Nếu ưu tiên đơn giản và chỉ chạy một gateway instance, `in-memory` có thể chấp nhận được. `redis-token-bucket` và `redis-fixed-window` cần tối ưu thêm trước khi dùng cho tải cao.

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

Với workload 200 request ở scenario `baseline`, `redis-sliding-window` là thuật toán tốt nhất trong bộ số liệu hiện tại: gateway p95 thấp nhất, rate limiter p95 thấp nhất và throughput cao nhất. Điều này cho thấy sliding window đang kiểm soát quota hiệu quả mà không tạo thêm tail latency lớn cho API gateway.

`in-memory` có thể dùng làm baseline đơn giản hoặc môi trường một gateway instance, nhưng không phù hợp nếu cần quota global trong hệ thống phân tán. `redis-token-bucket` và `redis-fixed-window` có overhead rate limiter rất cao trong lần đo này, vì vậy cần tối ưu Redis/script/connection pool trước khi kết luận chúng phù hợp với tải cao.

Kết luận định lượng này nên được xác nhận lại bằng nhiều trial hơn và các scenario `delay-100`, `delay-500`, `overload`, `burst` để tách rõ ảnh hưởng của backend latency, Redis contention và burst traffic.
