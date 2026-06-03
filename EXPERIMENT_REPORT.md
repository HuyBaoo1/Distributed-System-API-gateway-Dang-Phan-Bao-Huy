# Báo cáo thí nghiệm latency API Gateway

File này là khung báo cáo học thuật cho project. Không điền kết quả định lượng nếu chưa chạy thí nghiệm thật từ `run_latency_experiments.py`.

## Câu hỏi nghiên cứu

1. Thuật toán rate limiting nào tạo overhead thấp nhất trong gateway khi backend không có delay?
2. Khi backend latency tăng, phần overhead của rate limiter còn ảnh hưởng đáng kể đến p95/p99 gateway latency không?
3. Under burst traffic, fixed window, sliding window và token bucket khác nhau như thế nào về tỷ lệ `429`, `Retry-After`, và tail latency?
4. Khi Redis gặp lỗi, policy `fail-closed`, `fail-open`, hoặc `local-fallback` làm thay đổi tính an toàn và availability ra sao?

## Chiến lược được so sánh

- `in-memory`: bộ đếm local theo từng instance gateway, latency thấp nhưng không đảm bảo quota toàn cục nếu scale nhiều instance.
- `redis-fixed-window`: bộ đếm Redis theo cửa sổ thời gian cố định, đơn giản và rẻ nhưng có thể cho burst lớn quanh ranh giới window.
- `redis-sliding-window`: Redis sorted set theo timestamp request, kiểm soát quota chính xác hơn trong window trượt nhưng có chi phí Redis cao hơn.
- `redis-token-bucket`: Redis hash lưu token và thời điểm refill, cho phép burst trong giới hạn bucket và làm mượt lưu lượng theo thời gian.

## Metrics

- `clientObserved`: latency nhìn từ script benchmark.
- `gatewayHeader`: `X-Gateway-Latency-Ms`, thời gian xử lý tổng trong gateway.
- `backendHeader`: `X-Backend-Latency-Ms`, thời gian gateway proxy sang backend.
- `rateLimiterHeader`: `X-RateLimit-Latency-Ms`, thời gian kiểm tra rate limit.
- `throughputRequestsPerSecond`: số response/giây trong mỗi scenario.
- `rejectionRate`: `429 / totalResponses`.
- `p50`, `p95`, `p99`: dùng p95/p99 làm chỉ số chính cho tail latency.

## Protocol chạy thí nghiệm

1. Cài dependency:

   ```powershell
   python -m pip install -r requirements.txt
   ```

2. Chạy hệ thống nhiều gateway bằng Docker Compose:

   ```powershell
   docker compose up --build
   ```

3. Chạy ma trận thí nghiệm:

   ```powershell
   python run_latency_experiments.py
   ```

4. Vẽ biểu đồ và xuất CSV:

   ```powershell
   python plot_latency_report.py --manifest reports/manifest.json
   ```

5. Chạy burst experiment cho từng chiến lược cần phân tích:

   ```powershell
   python burst_behavior_experiment.py --label redis-fixed-window --url http://localhost:8082/api/v1/hello --align-to-window --output reports/burst-fixed-window.json
   python burst_behavior_experiment.py --label redis-token-bucket --url http://localhost:8083/api/v1/hello --align-to-window --output reports/burst-token-bucket.json
   ```

## Kết quả

Điền sau khi chạy script:

| Strategy | Scenario | Gateway p95 ms | Rate limiter p95 ms | Backend p95 ms | Throughput rps | Rejection rate |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `in-memory` | `baseline` | TBD | TBD | TBD | TBD | TBD |
| `redis-fixed-window` | `baseline` | TBD | TBD | TBD | TBD | TBD |
| `redis-sliding-window` | `baseline` | TBD | TBD | TBD | TBD | TBD |
| `redis-token-bucket` | `baseline` | TBD | TBD | TBD | TBD | TBD |

## Cách diễn giải

- Nếu `rateLimiterHeader.p95Ms` tăng nhưng `backendHeader.p95Ms` thấp, bottleneck nằm ở rate limiter hoặc Redis.
- Nếu `backendHeader.p95Ms` gần bằng `gatewayHeader.p95Ms`, downstream latency chi phối gateway latency.
- Nếu `clientObserved.p95Ms` cao hơn nhiều so với `gatewayHeader.p95Ms`, phần chênh lệch có thể đến từ client-side queueing, network, connection setup, hoặc scheduling của máy chạy benchmark.
- Nếu fixed window có số request thành công cao quanh ranh giới window, đó là boundary burst behavior chứ không phải lỗi đo đạc.
- Nếu token bucket giảm spike `429` nhưng vẫn giữ quota trung bình, nó phù hợp cho workload có burst ngắn.

## Threats to validity

- Benchmark chạy trên cùng máy với service có thể bị ảnh hưởng bởi CPU scheduling và Docker resource limits.
- Docker Desktop, JVM warm-up và Redis cold start có thể làm lệch vài batch request đầu.
- `requests` trong Python không mô phỏng đầy đủ connection pool hoặc behavior của production clients.
- Một Redis instance local không đại diện hoàn toàn cho Redis cluster nhiều node hoặc provider-managed Redis qua network xa.
- Kết quả phải được lặp lại nhiều lần trước khi kết luận định lượng.
